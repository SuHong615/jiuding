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
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Dev-only Mock OIDC (docs/23 §4) is not exposed unless
 * {@code tenancy.auth.mock-oidc.enabled=true}; when disabled this fallback answers a
 * deterministic 404 (instead of letting the shared exception handler surface a 500 for an
 * unmapped path).
 */
@RestController
@ConditionalOnProperty(prefix = "tenancy.auth.mock-oidc", name = "enabled",
        havingValue = "false", matchIfMissing = true)
public class MockOidcDisabledController {

    @RequestMapping({"/mock-oidc", "/mock-oidc/**"})
    public ResponseEntity<ApiResponse<Void>> disabled() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiResponse.error(ErrorCode.NOT_FOUND.code(), "mock OIDC is disabled"));
    }
}
