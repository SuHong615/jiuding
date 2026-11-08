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
import com.suhong.jiuding.tenant.permission.RequirePermission;
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

/** Platform user management API (docs/20 section 3), platform administrator JWT only. */
@RestController
@RequestMapping("/api/platform/users")
public class PlatformUserController {

    private final PlatformUserService userService;

    public PlatformUserController(PlatformUserService userService) {
        this.userService = userService;
    }

    @GetMapping
    @RequirePermission("rbac:read")
    public ApiResponse<List<PlatformUserView>> list(@RequestParam(required = false) String status,
                                                    @RequestParam(required = false) String keyword) {
        return ok(userService.list(status, keyword));
    }

    @PostMapping
    @RequirePermission("rbac:write")
    public ApiResponse<PlatformUserView> create(@Valid @RequestBody PlatformUserCreateRequest request) {
        return ok(userService.create(request));
    }

    @GetMapping("/{id}")
    @RequirePermission("rbac:read")
    public ApiResponse<PlatformUserView> get(@PathVariable String id) {
        return ok(userService.get(id));
    }

    @PutMapping("/{id}")
    @RequirePermission("rbac:write")
    public ApiResponse<PlatformUserView> update(@PathVariable String id,
                                                @Valid @RequestBody PlatformUserUpdateRequest request) {
        return ok(userService.update(id, request));
    }

    @DeleteMapping("/{id}")
    @RequirePermission("rbac:write")
    public ApiResponse<Void> delete(@PathVariable String id) {
        userService.delete(id);
        return ok(null);
    }

    @PostMapping("/{id}/reset-password")
    @RequirePermission("rbac:write")
    public ApiResponse<PlatformPasswordResetResult> resetPassword(
            @PathVariable String id, @RequestBody(required = false) PlatformUserResetRequest request) {
        return ok(userService.resetPassword(id, request));
    }

    private static <T> ApiResponse<T> ok(T data) {
        return ApiResponse.ok(data, MDC.get("requestId"));
    }
}
