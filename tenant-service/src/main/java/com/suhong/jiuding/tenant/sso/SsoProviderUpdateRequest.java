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
package com.suhong.jiuding.tenant.sso;

/**
 * Body of {@code PUT /api/platform/sso-providers/{id}} (docs/20 section 3). The provider code
 * is immutable; null fields keep their current value.
 */
public record SsoProviderUpdateRequest(
        String name,
        String issuer,
        String clientId,
        String clientSecret,
        String scopes,
        String redirectUri,
        String frontendRedirect,
        String roleClaim,
        String defaultRole,
        Boolean enabled) {
}
