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
package com.suhong.jiuding.tenant.offboarding;

import com.suhong.jiuding.common.api.ApiResponse;
import com.suhong.jiuding.tenant.api.TenantView;
import jakarta.validation.Valid;
import org.slf4j.MDC;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Tenant offboarding endpoint (docs/18 section 3), platform administrator only. */
@RestController
@RequestMapping("/api/tenants/{tenantId}")
public class TenantOffboardController {

    private final TenantOffboardService offboardService;

    public TenantOffboardController(TenantOffboardService offboardService) {
        this.offboardService = offboardService;
    }

    @PostMapping("/offboard")
    public ApiResponse<TenantView> offboard(@PathVariable String tenantId,
                                            @Valid @RequestBody OffboardRequest request) {
        return ApiResponse.ok(offboardService.offboard(tenantId, request), MDC.get("requestId"));
    }
}
