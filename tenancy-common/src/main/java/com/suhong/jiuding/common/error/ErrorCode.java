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
package com.suhong.jiuding.common.error;

/** Stable business error codes shared by HTTP APIs, MCP tools and the CLI. */
public enum ErrorCode {
    OK(0),
    INVALID_ARGUMENT(40001),
    UNAUTHORIZED(40100),
    FORBIDDEN(40300),
    NOT_FOUND(40400),
    CONFLICT(40900),
    TENANT_SUSPENDED(40301),
    QUOTA_EXCEEDED(42900),
    APPROVAL_INVALID_STATE(54000),
    SKILL_NOT_FOUND(54400),
    AGENT_ERROR(53000),
    UPSTREAM_ERROR(50200),
    INTERNAL(50000);

    private final int code;

    ErrorCode(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }
}
