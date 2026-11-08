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
package com.suhong.jiuding.tenant.scim;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** SCIM 2.0 service provider discovery document (RFC7643 section 5). */
@RestController
@RequestMapping(value = "/scim/v2", produces = ScimSupport.SCIM_JSON)
public class ScimServiceProviderConfigController {

    private final ScimTokenService tokenService;

    public ScimServiceProviderConfigController(ScimTokenService tokenService) {
        this.tokenService = tokenService;
    }

    @GetMapping("/ServiceProviderConfig")
    public Map<String, Object> serviceProviderConfig(HttpServletRequest request) {
        // Discovery also uses the tenant SCIM token so no cross-tenant metadata leaks.
        tokenService.authenticateTenant(request.getHeader(HttpHeaders.AUTHORIZATION));

        Map<String, Object> config = new LinkedHashMap<>();
        config.put("schemas", List.of("urn:ietf:params:scim:schemas:core:2.0:ServiceProviderConfig"));
        config.put("documentationUri", "/scim/v2/ServiceProviderConfig");
        config.put("patch", Map.of("supported", true));
        config.put("bulk", Map.of("supported", false, "maxOperations", 0, "maxPayloadSize", 0));
        config.put("filter", Map.of("supported", true, "maxResults", 100));
        config.put("changePassword", Map.of("supported", false));
        config.put("sort", Map.of("supported", false));
        config.put("etag", Map.of("supported", false));
        config.put("authenticationSchemes", List.of(Map.of(
                "type", "oauthbearertoken",
                "name", "SCIM Token",
                "description", "Tenant-scoped SCIM bearer token (Authorization: Bearer scim_...)",
                "primary", true)));
        return config;
    }
}
