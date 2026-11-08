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
package com.suhong.jiuding.tenant.workflow;

import com.suhong.jiuding.common.api.ApiResponse;
import com.suhong.jiuding.tenant.permission.RequirePermission;
import org.slf4j.MDC;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only tenant lifecycle workflow API (docs/20 section 4.5).
 *
 * <p>The endpoint is available to every authenticated caller that can read tenants or
 * organizations: {@code tenant:read} covers platform administrators, tenant administrators
 * and auditors, while {@code org:read} covers organization scoped roles such as ORG_ADMIN
 * and MEMBER (the annotation semantics are "any of"). Machine callers authenticated with the
 * internal token also pass the permission interceptor.
 */
@RestController
@RequestMapping("/api/workflows")
public class TenantLifecycleWorkflowController {

    /**
     * Returns the lifecycle stages, the per-stage skill/agent activities and the legal
     * transition edges. The payload is static reference data derived from the state machine
     * and the five built-in skills.
     */
    @GetMapping("/tenant-lifecycle")
    @RequirePermission({"tenant:read", "org:read"})
    public ApiResponse<TenantLifecycleWorkflowView> tenantLifecycle() {
        return ApiResponse.ok(TenantLifecycleWorkflowCatalog.snapshot(), MDC.get("requestId"));
    }
}
