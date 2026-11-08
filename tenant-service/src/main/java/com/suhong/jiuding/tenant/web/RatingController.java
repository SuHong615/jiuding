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
import com.suhong.jiuding.common.api.PageResult;
import com.suhong.jiuding.tenant.api.RatingAssessRequest;
import com.suhong.jiuding.tenant.api.RatingOverrideRequest;
import com.suhong.jiuding.tenant.api.RatingView;
import com.suhong.jiuding.tenant.service.RatingService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Tenant rating API (docs/11 section 6.4). */
@RestController
@RequestMapping("/api/tenants/{tenantId}/ratings")
public class RatingController {

    private final RatingService ratingService;

    public RatingController(RatingService ratingService) {
        this.ratingService = ratingService;
    }

    @GetMapping
    public ApiResponse<PageResult<RatingView>> history(
            @PathVariable String tenantId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponses.ok(ratingService.history(tenantId, page, size));
    }

    @GetMapping("/current")
    public ApiResponse<RatingView> current(@PathVariable String tenantId) {
        return ApiResponses.ok(ratingService.current(tenantId));
    }

    @PostMapping("/assess")
    public ApiResponse<RatingView> assess(@PathVariable String tenantId,
                                          @RequestBody(required = false) @Valid RatingAssessRequest request) {
        return ApiResponses.ok(ratingService.assess(tenantId, request));
    }

    @PostMapping("/override")
    public ApiResponse<RatingView> override(@PathVariable String tenantId,
                                            @Valid @RequestBody RatingOverrideRequest request) {
        return ApiResponses.ok(ratingService.override(tenantId, request));
    }
}
