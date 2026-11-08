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

/** JSON-RPC / MCP constants used across the module. */
public final class McpProtocol {

    /** JSON-RPC 2.0 standard error codes. */
    public static final int PARSE_ERROR = -32700;
    public static final int INVALID_REQUEST = -32600;
    public static final int METHOD_NOT_FOUND = -32601;
    public static final int INVALID_PARAMS = -32602;
    public static final int INTERNAL_ERROR = -32603;

    public static final String JSONRPC = "jsonrpc";
    public static final String METHOD = "method";
    public static final String PARAMS = "params";
    public static final String ID = "id";
    public static final String RESULT = "result";
    public static final String ERROR = "error";

    public static final String HEADER_SESSION_ID = "Mcp-Session-Id";
    public static final String HEADER_PROTOCOL_VERSION = "MCP-Protocol-Version";
    public static final String HEADER_ACCEL_BUFFERING = "X-Accel-Buffering";
    public static final String HEADER_API_KEY = "X-API-Key";
    public static final String HEADER_REQUEST_ID = "X-Request-Id";
    public static final String HEADER_AUTHORIZATION = "Authorization";
    /** Tenant scope forwarded to tenant-service for org/account/rating tools. */
    public static final String HEADER_TENANT_ID = "X-Tenant-Id";

    private McpProtocol() {
    }
}
