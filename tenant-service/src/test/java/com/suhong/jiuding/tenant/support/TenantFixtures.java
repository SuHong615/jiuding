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
package com.suhong.jiuding.tenant.support;

import com.suhong.jiuding.tenant.api.TenantCreateRequest;
import com.suhong.jiuding.tenant.api.TenantView;
import com.suhong.jiuding.tenant.service.TenantService;
import org.springframework.jdbc.core.JdbcTemplate;

/** Shared fixtures for the V2 identity-domain tests (org units / accounts / ratings). */
public final class TenantFixtures {

    private TenantFixtures() {
    }

    public static TenantCreateRequest request(String code, String name) {
        return new TenantCreateRequest(
                code,
                name,
                null,
                code + "@acme.io",
                null,
                "PRO",
                "cn-shanghai",
                "SHARED_SCHEMA",
                100,
                100,
                1000,
                "MANUAL",
                null,
                false);
    }

    public static TenantView createTenant(TenantService tenantService, String code, String name) {
        return tenantService.create(request(code, name));
    }

    /** Removes every row of the tenant_db tables used by these tests (no foreign keys). */
    public static void clean(JdbcTemplate jdbc) {
        jdbc.update("DELETE FROM account_login_events");
        jdbc.update("DELETE FROM tenant_ratings");
        jdbc.update("DELETE FROM accounts");
        jdbc.update("DELETE FROM org_units");
        jdbc.update("DELETE FROM tenant_audits");
        jdbc.update("DELETE FROM collect_jobs");
        jdbc.update("DELETE FROM tenants");
    }
}
