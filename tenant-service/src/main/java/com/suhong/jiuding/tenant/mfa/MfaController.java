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
import com.suhong.jiuding.tenant.permission.RequirePermission;
import jakarta.validation.Valid;
import org.slf4j.MDC;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Self-service MFA API (docs/16 section 3). Every endpoint operates on the authenticated
 * tenant account itself; there is no administrative override. The permission annotation
 * grants access to administrators (account:security) and to the account owner via the
 * self-service password permission (account:write, MEMBER holds ACCOUNT_CHANGE_PASSWORD);
 * the "self only" check stays in {@link MfaService}.
 */
@RestController
@RequestMapping("/api/me/mfa")
public class MfaController {

    private final MfaService mfaService;

    public MfaController(MfaService mfaService) {
        this.mfaService = mfaService;
    }

    @GetMapping
    @RequirePermission({"account:security", "account:write"})
    public ApiResponse<MfaStatusView> status() {
        return ok(mfaService.status());
    }

    @PostMapping("/setup")
    @RequirePermission({"account:security", "account:write"})
    public ApiResponse<MfaSetupResult> setup() {
        return ok(mfaService.setup());
    }

    @PostMapping("/verify")
    @RequirePermission({"account:security", "account:write"})
    public ApiResponse<MfaVerifyResult> verify(@Valid @RequestBody MfaCodeRequest request) {
        return ok(mfaService.verify(request));
    }

    @PostMapping("/disable")
    @RequirePermission({"account:security", "account:write"})
    public ApiResponse<MfaStatusView> disable(@Valid @RequestBody MfaCodeRequest request) {
        return ok(mfaService.disable(request));
    }

    private static <T> ApiResponse<T> ok(T data) {
        return ApiResponse.ok(data, MDC.get("requestId"));
    }
}
