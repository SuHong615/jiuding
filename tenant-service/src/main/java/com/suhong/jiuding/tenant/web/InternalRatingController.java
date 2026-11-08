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
import com.suhong.jiuding.tenant.api.RatingRefreshResult;
import com.suhong.jiuding.tenant.service.RatingRefreshService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Internal rating maintenance endpoint (docs/14 section 3.4): the k8s CronJob triggers the
 * scheduled rating refresh through this route with {@code X-Internal-Token} authentication
 * (enforced by tenancy-common's AuthFilter for every {@code /internal/**} path).
 */
@RestController
@RequestMapping("/internal/ratings")
public class InternalRatingController {

    private final RatingRefreshService refreshService;

    public InternalRatingController(RatingRefreshService refreshService) {
        this.refreshService = refreshService;
    }

    @PostMapping("/refresh")
    public ApiResponse<RatingRefreshResult> refresh() {
        RatingRefreshService.RefreshStats stats = refreshService.refreshConfigured();
        return ApiResponses.ok(new RatingRefreshResult(stats.refreshed(), stats.skipped(), stats.failed()));
    }
}
