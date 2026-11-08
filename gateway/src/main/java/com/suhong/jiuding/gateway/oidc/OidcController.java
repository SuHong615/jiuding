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

import com.suhong.jiuding.common.api.ApiResponse;
import com.suhong.jiuding.common.error.ErrorCode;
import com.suhong.jiuding.gateway.config.OidcProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.Map;

/**
 * OIDC / SSO endpoints (docs/16 §2), whitelisted in the gateway security config.
 *
 * <ul>
 *   <li>{@code GET /api/auth/oidc/status} – feature flag + provider name + login URL</li>
 *   <li>{@code GET /api/auth/oidc/authorize} – JSON with {@code authorizationUrl} (or a 302
 *       when {@code redirect=true}) carrying signed state + S256 PKCE challenge</li>
 *   <li>{@code GET /api/auth/oidc/callback} – code exchange → userinfo → JWT → 302 to the
 *       frontend with {@code #token=...&username=...}; failures redirect with {@code #error=...}</li>
 * </ul>
 * When OIDC is not enabled the status reports {@code enabled=false}, authorize answers 404 and
 * callback answers 400 (docs/16 §5).
 */
@RestController
@RequestMapping("/api/auth/oidc")
public class OidcController {

    private static final Logger log = LoggerFactory.getLogger(OidcController.class);

    private final OidcProperties properties;
    private final OidcService oidcService;

    public OidcController(OidcProperties properties, OidcService oidcService) {
        this.properties = properties;
        this.oidcService = oidcService;
    }

    @GetMapping("/status")
    public ApiResponse<Map<String, Object>> status() {
        return ApiResponse.ok(oidcService.status());
    }

    @GetMapping("/authorize")
    public ResponseEntity<?> authorize(@RequestParam(required = false) Boolean redirect) {
        if (!properties.isUsable()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(ApiResponse.error(ErrorCode.NOT_FOUND.code(), "OIDC is not enabled"));
        }
        String authorizationUrl;
        try {
            authorizationUrl = oidcService.buildAuthorizationUrl();
        } catch (OidcException ex) {
            log.warn("OIDC authorize failed: {}", ex.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(ApiResponse.error(ErrorCode.UPSTREAM_ERROR.code(),
                            "OIDC provider is unavailable"));
        }
        if (Boolean.TRUE.equals(redirect)) {
            return ResponseEntity.status(HttpStatus.FOUND)
                    .header(HttpHeaders.LOCATION, authorizationUrl)
                    .build();
        }
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("enabled", true);
        body.put("providerName", properties.getProviderName());
        body.put("authorizationUrl", authorizationUrl);
        return ResponseEntity.ok(ApiResponse.ok(body));
    }

    @GetMapping("/callback")
    public ResponseEntity<?> callback(@RequestParam(required = false) String code,
                                      @RequestParam(required = false) String state) {
        if (!properties.isUsable()) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.error(ErrorCode.INVALID_ARGUMENT.code(), "OIDC is not enabled"));
        }
        try {
            return redirect(oidcService.handleCallback(code, state));
        } catch (OidcException ex) {
            log.warn("OIDC callback failed ({}): {}", ex.errorCode(), ex.getMessage());
            return redirect(oidcService.errorRedirect(ex.errorCode()));
        }
    }

    private ResponseEntity<?> redirect(String location) {
        return ResponseEntity.status(HttpStatus.FOUND)
                .header(HttpHeaders.LOCATION, location)
                .build();
    }
}
