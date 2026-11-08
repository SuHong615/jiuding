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
package com.suhong.jiuding.tenant.retention;

import com.suhong.jiuding.common.api.ApiResponse;
import org.slf4j.MDC;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Internal maintenance endpoint (docs/15 section 8): the k8s CronJob triggers a retention
 * purge with {@code X-Internal-Token} (enforced by tenancy-common's AuthFilter for
 * {@code /internal/**}).
 */
@RestController
@RequestMapping("/internal/maintenance")
public class InternalMaintenanceController {

    private final RetentionService retentionService;

    public InternalMaintenanceController(RetentionService retentionService) {
        this.retentionService = retentionService;
    }

    @PostMapping("/purge")
    public ApiResponse<PurgeStats> purge() {
        return ApiResponse.ok(retentionService.purge(), MDC.get("requestId"));
    }
}
