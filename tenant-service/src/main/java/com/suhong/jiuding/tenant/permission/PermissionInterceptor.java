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
package com.suhong.jiuding.tenant.permission;

import com.suhong.jiuding.common.error.BizException;
import com.suhong.jiuding.common.error.ErrorCode;
import com.suhong.jiuding.common.security.AuthContext;
import com.suhong.jiuding.common.security.Principal;
import com.suhong.jiuding.common.tenant.TenantContext;
import com.suhong.jiuding.common.tenant.TenantScope;
import com.suhong.jiuding.tenant.support.SecurityScope;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Set;

/**
 * Enforces {@link RequirePermission} on annotated controller methods (v9 phase 1).
 *
 * <p>The permission layer is an additional gate: controllers still run their existing
 * {@code SecurityScope}/{@code TenantAccess} checks for tenant isolation and org subtree
 * boundaries. Platform administrators and trusted machine callers bypass it with {@code *}.
 */
@Component
public class PermissionInterceptor implements HandlerInterceptor {

    private final PermissionCache permissions;

    public PermissionInterceptor(PermissionCache permissions) {
        this.permissions = permissions;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod method)) {
            return true;
        }
        RequirePermission required = method.getMethodAnnotation(RequirePermission.class);
        if (required == null) {
            required = method.getBeanType().getAnnotation(RequirePermission.class);
        }
        if (required == null) {
            return true;
        }
        Set<String> requiredCodes = PermissionCatalog.requiredCodes(required.value());
        if (requiredCodes.isEmpty()) {
            return true;
        }

        Principal principal = AuthContext.get();
        if (principal == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "authentication required");
        }
        TenantScope scope = TenantContext.get();
        if ("INTERNAL_TOKEN".equals(principal.authType())) {
            return true;
        }
        if (principal.isPlatformAdmin() || SecurityScope.isPlatformWide(scope)) {
            return true;
        }
        Set<String> effective = permissions.permissions(principal.roles());
        if (effective.contains("*")) {
            return true;
        }
        for (String code : requiredCodes) {
            if (effective.contains(code)) {
                return true;
            }
        }
        throw BizException.forbidden("permission denied: missing required permission "
                + String.join(" | ", required.value()));
    }
}
