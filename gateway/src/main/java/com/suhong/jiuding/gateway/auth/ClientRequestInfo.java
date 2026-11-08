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
package com.suhong.jiuding.gateway.auth;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Client network identity forwarded to tenant-service for login-audit records
 * (docs/14 §4): resolved client IP plus the (truncated) User-Agent.
 */
public record ClientRequestInfo(String clientIp, String userAgent) {

    /** docs/14 §4: User-Agent is stored in a VARCHAR(256) column. */
    public static final int MAX_USER_AGENT_LENGTH = 256;

    public static ClientRequestInfo resolve(HttpServletRequest request) {
        return new ClientRequestInfo(resolveClientIp(request), truncatedUserAgent(request));
    }

    /**
     * {@code X-Forwarded-For} first segment (the original client behind proxies),
     * falling back to the servlet remote address.
     */
    public static String resolveClientIp(HttpServletRequest request) {
        String forwardedFor = request.getHeader("X-Forwarded-For");
        if (forwardedFor != null && !forwardedFor.isBlank()) {
            String first = forwardedFor.split(",")[0].trim();
            if (!first.isEmpty()) {
                return first;
            }
        }
        String remote = request.getRemoteAddr();
        return remote == null || remote.isBlank() ? "unknown" : remote;
    }

    public static String truncatedUserAgent(HttpServletRequest request) {
        String userAgent = request.getHeader("User-Agent");
        if (userAgent == null) {
            return null;
        }
        return userAgent.length() <= MAX_USER_AGENT_LENGTH
                ? userAgent
                : userAgent.substring(0, MAX_USER_AGENT_LENGTH);
    }
}
