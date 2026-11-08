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
package com.suhong.jiuding.mcp.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/** {@code GET /mcp} must be a real SSE stream with keep-alive comments. */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "tenancy.security.api-keys=test-key",
                "tenancy.mcp.sse-heartbeat-ms=150"
        })
class SseHeartbeatTest {

    @Value("${local.server.port}")
    private int port;

    @Test
    void sseStreamSendsConnectedCommentAndHeartbeats() throws Exception {
        HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/mcp"))
                .header("X-API-Key", "test-key")
                .header("Accept", "text/event-stream")
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build();

        HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type").orElse("")).contains("text/event-stream");
        assertThat(response.headers().firstValue("X-Accel-Buffering").orElse("")).isEqualTo("no");
        assertThat(response.headers().firstValue("Mcp-Session-Id").orElse("")).isNotBlank();

        String collected = readUntil(response.body(), ": ping", 5_000L);
        assertThat(collected).contains(": connected");
        assertThat(collected).contains(": ping");
    }

    private String readUntil(InputStream body, String needle, long timeoutMs) throws Exception {
        StringBuilder collected = new StringBuilder();
        byte[] buffer = new byte[1024];
        long deadline = System.currentTimeMillis() + timeoutMs;
        try {
            while (System.currentTimeMillis() < deadline) {
                int available = body.available();
                if (available > 0) {
                    int read = body.read(buffer, 0, Math.min(available, buffer.length));
                    if (read < 0) {
                        break;
                    }
                    collected.append(new String(buffer, 0, read, StandardCharsets.UTF_8));
                    if (collected.indexOf(needle) >= 0) {
                        break;
                    }
                } else {
                    Thread.sleep(20L);
                }
            }
        } finally {
            body.close();
        }
        return collected.toString();
    }
}
