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
package com.suhong.jiuding.tenant.platformuser;

import com.suhong.jiuding.common.api.ApiResponse;
import org.slf4j.MDC;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Internal platform user authentication for the gateway login chain (docs/20 section 3).
 * Caller authentication is the shared {@code X-Internal-Token} check in AuthFilter.
 */
@RestController
@RequestMapping("/internal/platform-users")
public class InternalPlatformUserController {

    private final PlatformUserService userService;

    public InternalPlatformUserController(PlatformUserService userService) {
        this.userService = userService;
    }

    @PostMapping("/authenticate")
    public ApiResponse<PlatformUserAuthResponse> authenticate(
            @RequestBody PlatformUserAuthRequest request) {
        return ApiResponse.ok(userService.authenticate(request), MDC.get("requestId"));
    }
}
