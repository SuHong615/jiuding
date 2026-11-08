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

import jakarta.validation.constraints.NotBlank;

/**
 * {@code POST /api/auth/login} body.
 *
 * <p>{@code tenantCode} is optional (docs/13 §2.1): when empty the gateway matches the
 * platform preset users first and falls back to tenant-account authentication; when
 * present the request is authenticated as a tenant account of that tenant.
 */
public record LoginRequest(@NotBlank String username, @NotBlank String password, String tenantCode) {
}
