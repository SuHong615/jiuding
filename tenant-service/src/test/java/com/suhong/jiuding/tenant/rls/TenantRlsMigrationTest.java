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
package com.suhong.jiuding.tenant.rls;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/** docs/23 section 2: V11 migration exists and the PostgreSQL policies are FORCE enabled. */
class TenantRlsMigrationTest {

    @Test
    void baseMigrationExistsForEveryEnvironment() throws IOException {
        String sql = resource("db/migration/V11__tenant_rls.sql");
        assertThat(sql).contains("tenant_rls", "PostgreSQL", "H2");
    }

    @Test
    void postgresMigrationEnablesAndForcesRlsOnAllTenantTables() throws IOException {
        String sql = resource("db/postgresql/V11_1__tenant_rls.sql");
        assertThat(sql).contains("ENABLE ROW LEVEL SECURITY", "FORCE ROW LEVEL SECURITY");
        assertThat(sql).contains("current_setting('app.platform', true) = 'true'");
        assertThat(sql).contains("current_setting('app.tenant_id', true)");
        for (String table : new String[]{"org_units", "accounts", "tenant_ratings",
                "tenant_audits", "account_login_events", "account_mfa"}) {
            assertThat(sql).contains("ALTER TABLE " + table + " ENABLE ROW LEVEL SECURITY;");
            assertThat(sql).contains("ALTER TABLE " + table + " FORCE ROW LEVEL SECURITY;");
            assertThat(sql).contains("CREATE POLICY " + table + "_tenant ON " + table);
            assertThat(sql).contains("WITH CHECK");
        }
    }

    private String resource(String path) throws IOException {
        try (InputStream stream = getClass().getClassLoader().getResourceAsStream(path)) {
            assertThat(stream).as("classpath resource %s", path).isNotNull();
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
