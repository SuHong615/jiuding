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
package com.suhong.jiuding.tenant.mfa;

import com.suhong.jiuding.common.api.ApiResponse;
import jakarta.validation.Valid;
import org.slf4j.MDC;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Self-service MFA API (docs/16 section 3). Every endpoint operates on the authenticated
 * tenant account itself; there is no administrative override.
 */
@RestController
@RequestMapping("/api/me/mfa")
public class MfaController {

    private final MfaService mfaService;

    public MfaController(MfaService mfaService) {
        this.mfaService = mfaService;
    }

    @GetMapping
    public ApiResponse<MfaStatusView> status() {
        return ok(mfaService.status());
    }

    @PostMapping("/setup")
    public ApiResponse<MfaSetupResult> setup() {
        return ok(mfaService.setup());
    }

    @PostMapping("/verify")
    public ApiResponse<MfaVerifyResult> verify(@Valid @RequestBody MfaCodeRequest request) {
        return ok(mfaService.verify(request));
    }

    @PostMapping("/disable")
    public ApiResponse<MfaStatusView> disable(@Valid @RequestBody MfaCodeRequest request) {
        return ok(mfaService.disable(request));
    }

    private static <T> ApiResponse<T> ok(T data) {
        return ApiResponse.ok(data, MDC.get("requestId"));
    }
}
