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

import com.suhong.jiuding.tenant.support.TimeUtil;

/**
 * Internal projection used by the gateway for the OIDC authorization-code flow; unlike the
 * public list it includes the client secret and redirect information (never exposed on the
 * public or admin API).
 */
public record InternalSsoProviderView(
        String id,
        String code,
        String name,
        String issuer,
        String clientId,
        String clientSecret,
        String scopes,
        String redirectUri,
        String frontendRedirect,
        String roleClaim,
        String defaultRole,
        String updatedAt) {

    public static InternalSsoProviderView of(SsoProviderRow row) {
        return new InternalSsoProviderView(
                row.id(),
                row.code(),
                row.name(),
                row.issuer(),
                row.clientId(),
                row.clientSecret(),
                row.scopes(),
                row.redirectUri(),
                row.frontendRedirect(),
                row.roleClaim(),
                row.defaultRole(),
                TimeUtil.iso(row.updatedAt()));
    }
}
