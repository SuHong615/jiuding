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
package com.suhong.jiuding.tenant.web;

import com.suhong.jiuding.common.api.ApiResponse;
import com.suhong.jiuding.tenant.api.AccountCountView;
import com.suhong.jiuding.tenant.api.RatingView;
import com.suhong.jiuding.tenant.api.RiskUpdateRequest;
import com.suhong.jiuding.tenant.api.StatusUpdateRequest;
import com.suhong.jiuding.tenant.api.TenantView;
import com.suhong.jiuding.tenant.service.AccountService;
import com.suhong.jiuding.tenant.service.RatingService;
import com.suhong.jiuding.tenant.service.TenantService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Internal service-to-service API. Authentication is enforced by tenancy-common's
 * {@code AuthFilter} via {@code X-Internal-Token}; these endpoints are never exposed
 * through the gateway and never rely on {@code TenantContext}.
 */
@RestController
@RequestMapping("/internal/tenants")
public class InternalTenantController {

    private final TenantService tenantService;
    private final AccountService accountService;
    private final RatingService ratingService;

    public InternalTenantController(TenantService tenantService,
                                    AccountService accountService,
                                    RatingService ratingService) {
        this.tenantService = tenantService;
        this.accountService = accountService;
        this.ratingService = ratingService;
    }

    @GetMapping("/{id}")
    public ApiResponse<TenantView> get(@PathVariable String id) {
        return ApiResponses.ok(tenantService.getInternal(id));
    }

    @PostMapping("/{id}/status")
    public ApiResponse<TenantView> updateStatus(@PathVariable String id,
                                                @Valid @RequestBody StatusUpdateRequest request) {
        return ApiResponses.ok(tenantService.updateStatusInternal(id, request.status(), request.reason()));
    }

    @PatchMapping("/{id}/risk")
    public ApiResponse<TenantView> updateRisk(@PathVariable String id,
                                              @Valid @RequestBody RiskUpdateRequest request) {
        return ApiResponses.ok(tenantService.updateRiskInternal(id, request.riskLevel(), request.riskScore()));
    }

    @GetMapping("/{id}/accounts/count")
    public ApiResponse<AccountCountView> accountCount(@PathVariable String id) {
        return ApiResponses.ok(new AccountCountView(accountService.countInternal(id)));
    }

    @GetMapping("/{id}/rating")
    public ApiResponse<RatingView> rating(@PathVariable String id) {
        return ApiResponses.ok(ratingService.currentInternal(id));
    }
}
