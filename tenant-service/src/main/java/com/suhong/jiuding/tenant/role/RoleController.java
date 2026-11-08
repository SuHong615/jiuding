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
package com.suhong.jiuding.tenant.role;

import com.suhong.jiuding.common.api.ApiResponse;
import com.suhong.jiuding.tenant.permission.RbacService;
import com.suhong.jiuding.tenant.permission.RoleView;
import jakarta.validation.Valid;
import org.slf4j.MDC;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Platform role management (docs/15 section 7.2). Platform administrator JWT only. */
@RestController
@RequestMapping("/api/platform/roles")
public class RoleController {

    private final RbacService rbacService;

    public RoleController(RbacService rbacService) {
        this.rbacService = rbacService;
    }

    @GetMapping
    public ApiResponse<List<RoleView>> list(@RequestParam(required = false) String scope) {
        return ok(rbacService.roles(scope));
    }

    @PostMapping
    public ApiResponse<RoleView> create(@Valid @RequestBody RoleCreateRequest request) {
        return ok(rbacService.createRole(request));
    }

    @PutMapping("/{code}/permissions")
    public ApiResponse<RoleView> updatePermissions(@PathVariable String code,
                                                   @Valid @RequestBody RolePermissionsRequest request) {
        return ok(rbacService.updatePermissions(code, request));
    }

    @DeleteMapping("/{code}")
    public ApiResponse<Void> delete(@PathVariable String code) {
        rbacService.deleteRole(code);
        return ok(null);
    }

    private static <T> ApiResponse<T> ok(T data) {
        return ApiResponse.ok(data, MDC.get("requestId"));
    }
}
