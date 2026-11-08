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
package com.suhong.jiuding.gateway.platform;

import com.suhong.jiuding.common.api.ApiResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** Local (non-proxied) platform aggregation endpoints. Authentication is enforced by the shared filter. */
@RestController
@RequestMapping("/api/platform")
public class PlatformController {

    private final PlatformService platformService;

    public PlatformController(PlatformService platformService) {
        this.platformService = platformService;
    }

    /**
     * Aggregates tenant / approval / agent overviews. The caller's JWT must be
     * forwarded downstream, otherwise every downstream call is rejected with 401
     * and the dashboard would silently show zeros.
     */
    @GetMapping("/overview")
    public ApiResponse<Map<String, Object>> overview(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        return ApiResponse.ok(platformService.overview(authorization));
    }

    @GetMapping("/health")
    public ApiResponse<List<ServiceHealth>> health() {
        return ApiResponse.ok(platformService.health());
    }
}
