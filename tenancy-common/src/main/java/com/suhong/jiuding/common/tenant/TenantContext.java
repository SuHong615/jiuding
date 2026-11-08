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
package com.suhong.jiuding.common.tenant;

/**
 * Thread-bound tenant scope. Every tenant-scoped SQL access MUST go through
 * {@link #require()} so that a missing scope fails closed instead of leaking rows
 * across tenants.
 */
public final class TenantContext {

    private static final ThreadLocal<TenantScope> HOLDER = new ThreadLocal<>();

    private TenantContext() {
    }

    public static void set(TenantScope scope) {
        HOLDER.set(scope);
    }

    public static TenantScope get() {
        return HOLDER.get();
    }

    public static TenantScope require() {
        TenantScope scope = HOLDER.get();
        if (scope == null || scope.tenantId() == null || scope.tenantId().isBlank()) {
            throw new com.suhong.jiuding.common.error.BizException(
                    com.suhong.jiuding.common.error.ErrorCode.UNAUTHORIZED, "tenant scope is missing");
        }
        return scope;
    }

    public static String tenantId() {
        return require().tenantId();
    }

    public static void clear() {
        HOLDER.remove();
    }
}
