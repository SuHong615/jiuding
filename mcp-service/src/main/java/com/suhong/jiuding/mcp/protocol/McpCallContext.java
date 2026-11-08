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
package com.suhong.jiuding.mcp.protocol;

/**
 * Per-request context (caller credentials, request id) propagated to tool
 * execution so downstream calls can forward the identity.
 */
public final class McpCallContext {

    /** Immutable per-request caller data. */
    public record Call(String authorization, String apiKey, String requestId, String sessionId) {
    }

    private static final ThreadLocal<Call> HOLDER = new ThreadLocal<>();

    private McpCallContext() {
    }

    public static void set(Call call) {
        HOLDER.set(call);
    }

    public static Call get() {
        return HOLDER.get();
    }

    public static void clear() {
        HOLDER.remove();
    }
}
