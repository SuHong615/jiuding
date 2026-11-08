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
package com.suhong.jiuding.gateway.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Minimal downstream stub built on the JDK's own {@link HttpServer} (no extra
 * test dependency). Records every request so tests can assert what the gateway
 * actually forwarded.
 */
public final class StubServer implements AutoCloseable {

    /** Captured request as seen by the "downstream service". */
    public record Recorded(String method, String path, String query, Map<String, String> headers, String body,
                           int sequence) {
        public String header(String name) {
            return headers.get(name.toLowerCase(Locale.ROOT));
        }
    }

    /** Callback invoked on every request; must send response headers/body. */
    public interface Handler {
        void handle(Recorded request, HttpExchange exchange) throws IOException;
    }

    private final HttpServer server;
    private final List<Recorded> requests = new CopyOnWriteArrayList<>();
    private final AtomicInteger counter = new AtomicInteger();

    public StubServer(Handler handler) {
        try {
            this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        this.server.createContext("/", exchange -> {
            try {
                byte[] body = exchange.getRequestBody().readAllBytes();
                Map<String, String> headers = new LinkedHashMap<>();
                exchange.getRequestHeaders().forEach((name, values) -> headers.put(
                        name.toLowerCase(Locale.ROOT), String.join(",", values)));
                Recorded recorded = new Recorded(
                        exchange.getRequestMethod(),
                        exchange.getRequestURI().getPath(),
                        exchange.getRequestURI().getRawQuery(),
                        headers,
                        new String(body, StandardCharsets.UTF_8),
                        counter.incrementAndGet());
                requests.add(recorded);
                handler.handle(recorded, exchange);
            } catch (Exception ex) {
                try {
                    respond(exchange, 500, "application/json", "{\"code\":50000,\"message\":\"stub failure\"}");
                } catch (IOException ignored) {
                    // client gone
                }
            } finally {
                exchange.close();
            }
        });
        this.server.setExecutor(Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "stub-server");
            thread.setDaemon(true);
            return thread;
        }));
        this.server.start();
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public List<Recorded> requests() {
        return List.copyOf(requests);
    }

    public Recorded lastRequest() {
        List<Recorded> snapshot = requests();
        return snapshot.isEmpty() ? null : snapshot.get(snapshot.size() - 1);
    }

    public void reset() {
        requests.clear();
    }

    public static void respond(HttpExchange exchange, int status, String contentType, String body) throws IOException {
        byte[] bytes = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
        if (contentType != null) {
            exchange.getResponseHeaders().set("Content-Type", contentType);
        }
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
                out.flush();
            }
        }
    }

    /** Sends a chunked (streamed) response, flushing each chunk like an SSE endpoint. */
    public static void stream(HttpExchange exchange, int status, String contentType, String... chunks)
            throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, 0);
        try (OutputStream out = exchange.getResponseBody()) {
            for (String chunk : chunks) {
                out.write(chunk.getBytes(StandardCharsets.UTF_8));
                out.flush();
            }
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
