/*
 * Copyright 2026 suhong
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * @author suhong
 */
package com.suhong.jiuding.gateway.proxy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.suhong.jiuding.common.api.ApiResponse;
import com.suhong.jiuding.common.error.ErrorCode;
import com.suhong.jiuding.common.security.Headers;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Enumeration;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Streaming reverse proxy on top of {@link java.net.http.HttpClient}.
 *
 * <p>Design notes:
 * <ul>
 *   <li>Request/response bodies are streamed chunk by chunk with a flush after each
 *       read: SSE ({@code text/event-stream}) reaches the client immediately and is
 *       never buffered in memory. That is why {@code BodyHandlers.ofInputStream()}
 *       is used instead of {@code ofString()}.</li>
 *   <li>{@code Content-Type}, status and {@code Mcp-Session-Id} are relayed verbatim;
 *       hop-by-hop headers and framing headers are dropped.</li>
 *   <li>{@code Authorization}, {@code X-API-Key}, {@code X-Tenant-Id},
 *       {@code X-Request-Id} and every other end-to-end header are forwarded.</li>
 *   <li>A downstream failure never propagates as 500: it becomes a 502
 *       {@link ApiResponse} with {@code UPSTREAM_ERROR}.</li>
 * </ul>
 */
@Service
public class GatewayProxyService {

    private static final Logger log = LoggerFactory.getLogger(GatewayProxyService.class);

    /** Never forwarded in either direction. */
    private static final Set<String> HOP_BY_HOP = Set.of(
            "connection", "keep-alive", "proxy-authenticate", "proxy-authorization",
            "te", "trailer", "transfer-encoding", "upgrade");

    /** Request headers that must be recomputed by the JDK client / servlet container. */
    private static final Set<String> SKIP_REQUEST_HEADERS = Set.of(
            "host", "content-length", "expect", "accept-encoding", "cookie");

    /** Response headers that would corrupt the relayed body when copied. */
    private static final Set<String> SKIP_RESPONSE_HEADERS = Set.of(
            "content-length", "content-type", "content-encoding");

    private static final int BUFFER_SIZE = 8 * 1024;

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public GatewayProxyService(HttpClient httpClient, ObjectMapper objectMapper) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
    }

    public void forward(HttpServletRequest request, HttpServletResponse response, ServiceRoute route) throws IOException {
        String requestId = request.getHeader(Headers.REQUEST_ID);
        if (requestId == null || requestId.isBlank()) {
            requestId = UUID.randomUUID().toString();
        }
        URI target = buildTargetUri(route.baseUrl(), request);
        HttpRequest upstreamRequest;
        try {
            upstreamRequest = buildUpstreamRequest(request, target, requestId, route.timeoutMs());
        } catch (RuntimeException ex) {
            log.warn("cannot build upstream request for {}: {}", target, ex.getMessage());
            writeUpstreamError(response, route.service(), requestId, ex);
            return;
        }

        HttpResponse<InputStream> upstream;
        try {
            upstream = httpClient.send(upstreamRequest, HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            writeUpstreamError(response, route.service(), requestId, ex);
            return;
        } catch (Exception ex) {
            log.warn("upstream {} call failed: {} {}", route.service(), target, ex.toString());
            writeUpstreamError(response, route.service(), requestId, ex);
            return;
        }
        relay(upstream, response, requestId);
    }

    private void relay(HttpResponse<InputStream> upstream, HttpServletResponse response, String requestId)
            throws IOException {
        response.setStatus(upstream.statusCode());
        upstream.headers().map().forEach((name, values) -> {
            String lower = name.toLowerCase(Locale.ROOT);
            if (HOP_BY_HOP.contains(lower) || SKIP_RESPONSE_HEADERS.contains(lower)) {
                return;
            }
            for (String value : values) {
                response.addHeader(name, value);
            }
        });
        // Content-Type is relayed verbatim (including the SSE one) and X-Request-Id is echoed.
        upstream.headers().firstValue("Content-Type")
                .ifPresent(contentType -> response.setContentType(contentType));
        response.setHeader(Headers.REQUEST_ID, requestId);

        try (InputStream body = upstream.body(); OutputStream out = response.getOutputStream()) {
            byte[] buffer = new byte[BUFFER_SIZE];
            int read;
            while ((read = body.read(buffer)) != -1) {
                out.write(buffer, 0, read);
                // Flush per chunk: required for SSE / MCP streaming, harmless for JSON.
                out.flush();
            }
        } catch (IOException ex) {
            // The client disconnected mid-stream; nothing left to report.
            log.debug("proxy stream for request {} closed early: {}", requestId, ex.getMessage());
        }
    }

    private HttpRequest buildUpstreamRequest(HttpServletRequest request, URI target,
                                             String requestId, int timeoutMs) throws IOException {
        byte[] body = request.getInputStream().readAllBytes();
        HttpRequest.BodyPublisher publisher = body.length == 0
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofByteArray(body);

        HttpRequest.Builder builder = HttpRequest.newBuilder(target)
                .timeout(Duration.ofMillis(timeoutMs))
                .method(request.getMethod(), publisher);

        Enumeration<String> headerNames = request.getHeaderNames();
        while (headerNames != null && headerNames.hasMoreElements()) {
            String name = headerNames.nextElement();
            String lower = name.toLowerCase(Locale.ROOT);
            if (HOP_BY_HOP.contains(lower) || SKIP_REQUEST_HEADERS.contains(lower)) {
                continue;
            }
            Enumeration<String> values = request.getHeaders(name);
            while (values.hasMoreElements()) {
                String value = values.nextElement();
                if (value == null) {
                    continue;
                }
                try {
                    builder.header(name, value);
                } catch (IllegalArgumentException ex) {
                    log.debug("restricted request header ignored: {}", name);
                }
            }
        }
        builder.setHeader(Headers.REQUEST_ID, requestId);
        return builder.build();
    }

    private URI buildTargetUri(String baseUrl, HttpServletRequest request) {
        String base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        String path = request.getRequestURI();
        String query = request.getQueryString();
        StringBuilder url = new StringBuilder(base).append(path);
        if (query != null && !query.isEmpty()) {
            url.append('?').append(query);
        }
        return URI.create(url.toString());
    }

    private void writeUpstreamError(HttpServletResponse response, String service,
                                    String requestId, Exception cause) throws IOException {
        if (response.isCommitted()) {
            log.warn("upstream {} failed after response was committed: {}", service, cause.toString());
            return;
        }
        response.setStatus(HttpServletResponse.SC_BAD_GATEWAY);
        response.setContentType("application/json;charset=UTF-8");
        response.setHeader(Headers.REQUEST_ID, requestId);
        String reason = cause.getClass().getSimpleName();
        String message = "upstream service unavailable: " + service + " (" + reason + ")";
        String json = objectMapper.writeValueAsString(
                ApiResponse.error(ErrorCode.UPSTREAM_ERROR.code(), message, requestId));
        response.getOutputStream().write(json.getBytes(StandardCharsets.UTF_8));
        response.getOutputStream().flush();
    }
}
