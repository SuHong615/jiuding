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

import com.suhong.jiuding.common.security.SecurityProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the public OIDC provider list with the shared AuthFilter whitelist.
 *
 * <p>The whitelist is a runtime property of the shared {@link SecurityProperties} bean; adding
 * the path here avoids editing {@code application.yml} (which belongs to the shared deployment
 * configuration) while still exposing the documented public endpoint.
 */
@Configuration
public class SsoPublicPathConfig {

    static final String PUBLIC_PROVIDERS_PATH = "/api/auth/oidc/providers";

    public SsoPublicPathConfig(SecurityProperties securityProperties) {
        if (securityProperties.getWhitelist() != null
                && !securityProperties.getWhitelist().contains(PUBLIC_PROVIDERS_PATH)) {
            securityProperties.getWhitelist().add(PUBLIC_PROVIDERS_PATH);
        }
    }
}
