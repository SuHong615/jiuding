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
package com.suhong.jiuding.tenant.scim;

import com.suhong.jiuding.common.api.ApiResponse;
import jakarta.validation.Valid;
import org.slf4j.MDC;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Platform SCIM token management (docs/16 section 7.1). Requires a platform administrator
 * JWT; the clear text token is returned only from the creation endpoint.
 */
@RestController
@RequestMapping("/api/platform/scim-tokens")
public class ScimTokenController {

    private final ScimTokenService tokenService;

    public ScimTokenController(ScimTokenService tokenService) {
        this.tokenService = tokenService;
    }

    @GetMapping
    public ApiResponse<List<ScimTokenView>> list(@RequestParam(required = false) String tenantId) {
        return ApiResponse.ok(tokenService.list(tenantId), MDC.get("requestId"));
    }

    @PostMapping
    public ApiResponse<ScimTokenCreatedView> create(@Valid @RequestBody ScimTokenCreateRequest request) {
        return ApiResponse.ok(tokenService.create(request), MDC.get("requestId"));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<ScimTokenView> revoke(@PathVariable String id) {
        return ApiResponse.ok(tokenService.revoke(id), MDC.get("requestId"));
    }
}
