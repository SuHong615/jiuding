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
import com.suhong.jiuding.tenant.permission.RequirePermission;
import org.slf4j.MDC;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Platform SSO provider management (docs/20 section 3), platform administrator JWT only. */
@RestController
@RequestMapping("/api/platform/sso-providers")
public class SsoProviderController {

    private final SsoProviderService providerService;

    public SsoProviderController(SsoProviderService providerService) {
        this.providerService = providerService;
    }

    @GetMapping
    @RequirePermission("rbac:read")
    public ApiResponse<List<SsoProviderView>> list() {
        return ok(providerService.list());
    }

    @PostMapping
    @RequirePermission("rbac:write")
    public ApiResponse<SsoProviderView> create(@RequestBody SsoProviderCreateRequest request) {
        return ok(providerService.create(request));
    }

    @GetMapping("/{id}")
    @RequirePermission("rbac:read")
    public ApiResponse<SsoProviderView> get(@PathVariable String id) {
        return ok(providerService.get(id));
    }

    @PutMapping("/{id}")
    @RequirePermission("rbac:write")
    public ApiResponse<SsoProviderView> update(@PathVariable String id,
                                               @RequestBody SsoProviderUpdateRequest request) {
        return ok(providerService.update(id, request));
    }

    @DeleteMapping("/{id}")
    @RequirePermission("rbac:write")
    public ApiResponse<Void> delete(@PathVariable String id) {
        providerService.delete(id);
        return ok(null);
    }

    @PostMapping("/{id}/test")
    @RequirePermission("rbac:write")
    public ApiResponse<SsoProviderTestResult> test(@PathVariable String id) {
        return ok(providerService.test(id));
    }

    private static <T> ApiResponse<T> ok(T data) {
        return ApiResponse.ok(data, MDC.get("requestId"));
    }
}
