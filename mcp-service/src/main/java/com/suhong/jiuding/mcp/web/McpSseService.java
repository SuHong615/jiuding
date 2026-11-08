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

import com.suhong.jiuding.mcp.config.McpProperties;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Server-Sent Events transport for MCP clients ({@code GET /mcp}).
 *
 * <p>Every connection receives an immediate {@code : connected} comment and then a
 * {@code : ping} comment every {@code tenancy.mcp.sse-heartbeat-ms} (15s by default).
 * Comments are valid SSE events that keep proxies/nginx from closing an idle stream.
 */
@Service
public class McpSseService {

    private static final Logger log = LoggerFactory.getLogger(McpSseService.class);

    private final McpProperties properties;
    private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();
    private ScheduledExecutorService heartbeatScheduler;

    public McpSseService(McpProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    public void startHeartbeat() {
        heartbeatScheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "mcp-sse-heartbeat");
            thread.setDaemon(true);
            return thread;
        });
        long interval = Math.max(50L, properties.getSseHeartbeatMs());
        heartbeatScheduler.scheduleAtFixedRate(this::sendHeartbeat, interval, interval, TimeUnit.MILLISECONDS);
        log.info("MCP SSE heartbeat enabled every {} ms", interval);
    }

    @PreDestroy
    public void stopHeartbeat() {
        if (heartbeatScheduler != null) {
            heartbeatScheduler.shutdownNow();
        }
        emitters.forEach(SseEmitter::complete);
        emitters.clear();
    }

    public SseEmitter subscribe(String sessionId) {
        SseEmitter emitter = new SseEmitter(properties.getSseTimeoutMs());
        emitters.add(emitter);
        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> {
            emitters.remove(emitter);
            emitter.complete();
        });
        emitter.onError(error -> emitters.remove(emitter));
        try {
            // SseEmitter renders comments as ":<text>"; the leading space produces ": connected ...".
            emitter.send(SseEmitter.event().comment(" connected session=" + sessionId));
        } catch (IOException ex) {
            emitters.remove(emitter);
            log.debug("SSE client for session {} left before greeting", sessionId);
        }
        return emitter;
    }

    public int activeConnections() {
        return emitters.size();
    }

    private void sendHeartbeat() {
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().comment(" ping"));
            } catch (Exception ex) {
                emitters.remove(emitter);
                try {
                    emitter.complete();
                } catch (Exception ignored) {
                    // already closed
                }
            }
        }
    }
}
