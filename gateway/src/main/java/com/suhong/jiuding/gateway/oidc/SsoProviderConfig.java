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
package com.suhong.jiuding.gateway.oidc;

/**
 * An enabled SSO provider loaded from tenant-service
 * {@code GET /internal/sso-providers/enabled} (docs/20 §2/§3). The {@code clientSecret} is
 * only used server-side for the token exchange and never exposed by the public API.
 */
public record SsoProviderConfig(String code,
                                String name,
                                String issuer,
                                String clientId,
                                String clientSecret,
                                String scopes,
                                String redirectUri,
                                String frontendRedirect,
                                String roleClaim,
                                String defaultRole) {

    public boolean isUsable() {
        return hasText(code) && hasText(issuer) && hasText(clientId);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
