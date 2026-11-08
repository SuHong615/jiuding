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
package com.suhong.jiuding.tenant.api;

/**
 * Body of {@code POST /internal/auth/switch-tenant} (docs/20 section 3).
 *
 * <p>The gateway forwards the credentials of the currently signed-in account together with
 * the target tenant code; tenant-service validates that the target tenant contains the same
 * username and password hash before the gateway issues a new tenant-scoped JWT.
 */
public record SwitchTenantRequest(String username, String tenantCode, String password) {
}
