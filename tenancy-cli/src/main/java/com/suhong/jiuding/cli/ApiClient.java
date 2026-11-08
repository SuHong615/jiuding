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
package com.suhong.jiuding.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.NullNode;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Thin HTTP client around {@code java.net.http.HttpClient} that understands the
 * project's unified {@code ApiResponse} envelope and translates failures into
 * {@link ApiException}s carrying the right CLI exit code.
 */
public final class ApiClient {

    private final HttpClient http;
    private final String baseUrl;
    private final String token;
    private final String tenantId;
    private final Duration timeout;

    public ApiClient(String baseUrl, String token, String tenantId, int timeoutSeconds) {
        this.baseUrl = stripTrailingSlash(baseUrl);
        this.token = blankToNull(token);
        this.tenantId = blankToNull(tenantId);
        this.timeout = Duration.ofSeconds(Math.max(1, timeoutSeconds));
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public String baseUrl() {
        return baseUrl;
    }

    public JsonNode get(String path, Map<String, String> query) {
        return send("GET", path, query, null, null);
    }

    public JsonNode post(String path, Object body) {
        return send("POST", path, null, body, null);
    }

    public JsonNode put(String path, Object body) {
        return send("PUT", path, null, body, null);
    }

    public JsonNode patch(String path, Object body) {
        return send("PATCH", path, null, body, null);
    }

    public JsonNode delete(String path, Object body) {
        return send("DELETE", path, null, body, null);
    }

    /** Raw (non-ApiResponse) GET used for CSV/plain-text downloads such as audit export. */
    public RawResponse getRaw(String path, Map<String, String> query) {
        HttpRequest request = requestBuilder(path, query, null)
                .header("Accept", "text/csv, text/plain, application/json")
                .GET()
                .build();
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (ConnectException e) {
            throw new ApiException(ExitCodes.NETWORK, -1,
                    "无法连接 " + request.uri() + ": " + rootMessage(e), e);
        } catch (IOException e) {
            throw new ApiException(ExitCodes.NETWORK, -1,
                    "请求失败 " + request.uri() + ": " + rootMessage(e), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException(ExitCodes.NETWORK, -1, "请求被中断: " + request.uri(), e);
        }
        String body = response.body() == null ? "" : response.body();
        if (response.statusCode() >= 400) {
            JsonNode node = tryParse(body);
            int apiCode = node != null && node.has("code") && node.get("code").isNumber()
                    ? node.get("code").asInt() : response.statusCode();
            String message = node != null && node.hasNonNull("message")
                    ? node.get("message").asText() : "HTTP " + response.statusCode();
            throw new ApiException(ExitCodes.forHttpStatus(response.statusCode()), apiCode, message);
        }
        String contentType = response.headers().firstValue("content-type").orElse("application/octet-stream");
        return new RawResponse(response.statusCode(), contentType, body);
    }

    /** Raw HTTP response body plus metadata (no envelope unwrapping). */
    public record RawResponse(int statusCode, String contentType, String body) {
    }

    /** Raw JSON-RPC call (MCP endpoint): returns the full JSON-RPC document, not the ApiResponse data. */
    public JsonNode postRpc(String path, String jsonBody, Map<String, String> extraHeaders) {
        HttpRequest request = requestBuilder(path, null, extraHeaders)
                .header("Content-Type", "application/json; charset=UTF-8")
                .header("Accept", "application/json, text/event-stream")
                .header("MCP-Protocol-Version", "2025-06-18")
                .method("POST", HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8))
                .build();
        return execute(request, true);
    }

    /** Multipart upload used by `collect import --file *.csv`. */
    public JsonNode postMultipart(String path, Path file, String fieldName, Map<String, String> fields) {
        String boundary = "----TenancyCli" + UUID.randomUUID().toString().replace("-", "");
        byte[] body;
        try {
            body = multipartBody(boundary, file, fieldName, fields);
        } catch (IOException e) {
            throw new CliUsageException("读取上传文件失败: " + file + " (" + e.getMessage() + ")");
        }
        HttpRequest request = requestBuilder(path, null, null)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
        return execute(request, false);
    }

    private byte[] multipartBody(String boundary, Path file, String fieldName, Map<String, String> fields)
            throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (fields != null) {
            for (Map.Entry<String, String> entry : fields.entrySet()) {
                out.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
                out.write(("Content-Disposition: form-data; name=\"" + entry.getKey() + "\"\r\n\r\n")
                        .getBytes(StandardCharsets.UTF_8));
                out.write((entry.getValue() == null ? "" : entry.getValue()).getBytes(StandardCharsets.UTF_8));
                out.write("\r\n".getBytes(StandardCharsets.UTF_8));
            }
        }
        String fileName = file.getFileName().toString();
        String contentType = fileName.toLowerCase().endsWith(".csv") ? "text/csv" : "application/json";
        out.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(("Content-Disposition: form-data; name=\"" + fieldName + "\"; filename=\"" + fileName + "\"\r\n")
                .getBytes(StandardCharsets.UTF_8));
        out.write(("Content-Type: " + contentType + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(Files.readAllBytes(file));
        out.write("\r\n".getBytes(StandardCharsets.UTF_8));
        out.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return out.toByteArray();
    }

    private JsonNode send(String method, String path, Map<String, String> query, Object body,
                          Map<String, String> extraHeaders) {
        HttpRequest.Builder builder = requestBuilder(path, query, extraHeaders);
        HttpRequest.BodyPublisher publisher;
        if (body == null) {
            publisher = HttpRequest.BodyPublishers.noBody();
        } else {
            String json = body instanceof String text ? text : Json.toJson(body);
            builder.header("Content-Type", "application/json; charset=UTF-8");
            publisher = HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8);
        }
        builder.method(method, publisher);
        return execute(builder.build(), false);
    }

    private HttpRequest.Builder requestBuilder(String path, Map<String, String> query,
                                               Map<String, String> extraHeaders) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url(path, query)))
                .timeout(timeout)
                .header("Accept", "application/json")
                .header("X-Request-Id", UUID.randomUUID().toString());
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        if (tenantId != null) {
            builder.header("X-Tenant-Id", tenantId);
        }
        if (extraHeaders != null) {
            extraHeaders.forEach((key, value) -> {
                if (value != null) {
                    builder.header(key, value);
                }
            });
        }
        return builder;
    }

    private JsonNode execute(HttpRequest request, boolean rawRpc) {
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (ConnectException e) {
            throw new ApiException(ExitCodes.NETWORK, -1,
                    "无法连接 " + request.uri() + ": " + rootMessage(e), e);
        } catch (IOException e) {
            throw new ApiException(ExitCodes.NETWORK, -1,
                    "请求失败 " + request.uri() + ": " + rootMessage(e), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException(ExitCodes.NETWORK, -1, "请求被中断: " + request.uri(), e);
        }

        String bodyText = response.body() == null ? "" : response.body().trim();
        JsonNode node = bodyText.isEmpty() ? null : tryParse(bodyText);
        int status = response.statusCode();

        if (status >= 400) {
            int apiCode = node != null && node.has("code") && node.get("code").isNumber()
                    ? node.get("code").asInt() : status;
            String message = node != null && node.hasNonNull("message")
                    ? node.get("message").asText() : "HTTP " + status;
            throw new ApiException(ExitCodes.forHttpStatus(status), apiCode, message);
        }
        if (rawRpc || node == null || !node.has("code") || !node.get("code").isNumber()) {
            return node == null ? NullNode.getInstance() : node;
        }
        int envelopeCode = node.get("code").asInt();
        if (envelopeCode != 0) {
            String message = node.hasNonNull("message") ? node.get("message").asText() : "业务错误";
            throw new ApiException(ExitCodes.forEnvelopeCode(envelopeCode), envelopeCode, message);
        }
        return node.has("data") ? node.get("data") : NullNode.getInstance();
    }

    private static JsonNode tryParse(String text) {
        try {
            return Json.mapper().readTree(text);
        } catch (IOException e) {
            return null;
        }
    }

    private String url(String path, Map<String, String> query) {
        StringBuilder sb = new StringBuilder(baseUrl);
        String normalized = path.startsWith("/") ? path : "/" + path;
        sb.append(normalized);
        Map<String, String> params = new LinkedHashMap<>();
        if (query != null) {
            query.forEach((key, value) -> {
                if (value != null && !value.isBlank()) {
                    params.put(key, value);
                }
            });
        }
        if (!params.isEmpty()) {
            sb.append('?');
            boolean first = true;
            for (Map.Entry<String, String> entry : params.entrySet()) {
                if (!first) {
                    sb.append('&');
                }
                sb.append(encode(entry.getKey())).append('=').append(encode(entry.getValue()));
                first = false;
            }
        }
        return sb.toString();
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static String rootMessage(Throwable throwable) {
        Throwable t = throwable;
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        return t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
    }
}
