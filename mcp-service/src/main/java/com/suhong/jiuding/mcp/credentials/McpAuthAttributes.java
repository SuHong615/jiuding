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
package com.suhong.jiuding.mcp.credentials;

/** Request attribute names used by the DB-key authentication filter and the audit recorder. */
public final class McpAuthAttributes {

    public static final String KEY_ID = "mcp.key.id";
    public static final String KEY_PREFIX = "mcp.key.prefix";
    public static final String OWNER_USERNAME = "mcp.key.owner";
    public static final String TENANT_ID = "mcp.key.tenantId";
    public static final String STATIC_KEY = "mcp.key.static";
    public static final String STATIC_PREFIX = "static";

    private McpAuthAttributes() {
    }
}
