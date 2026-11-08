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
package com.suhong.jiuding.tenant.sso;

import com.suhong.jiuding.common.api.ApiResponse;
import org.slf4j.MDC;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Enabled SSO provider list for the gateway (docs/20 section 3), protected by the shared
 * {@code X-Internal-Token} check for {@code /internal/**} paths.
 */
@RestController
@RequestMapping("/internal/sso-providers")
public class InternalSsoProviderController {

    private final SsoProviderService providerService;

    public InternalSsoProviderController(SsoProviderService providerService) {
        this.providerService = providerService;
    }

    @GetMapping("/enabled")
    public ApiResponse<List<InternalSsoProviderView>> enabled() {
        return ApiResponse.ok(providerService.internalEnabled(), MDC.get("requestId"));
    }
}
