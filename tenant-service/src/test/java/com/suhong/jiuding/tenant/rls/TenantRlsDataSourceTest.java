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

import com.suhong.jiuding.common.security.AuthContext;
import com.suhong.jiuding.common.security.Principal;
import com.suhong.jiuding.common.tenant.TenantContext;
import com.suhong.jiuding.common.tenant.TenantScope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** docs/23 section 2: connection level RLS GUC configuration and reset. */
class TenantRlsDataSourceTest {

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        AuthContext.clear();
    }

    @Test
    void tenantScopedConnectionSetsTenantIdAndResetsOnClose() throws Exception {
        Fixture fixture = new Fixture();
        TenantContext.set(new TenantScope("tenant-1", "u-1", Set.of("TENANT_ADMIN")));
        AuthContext.set(new Principal("u-1", "alice", "tenant-1", Set.of("TENANT_ADMIN"), "JWT"));

        try (Connection ignored = fixture.dataSource().getConnection()) {
            assertThat(ignored).isNotNull();
        }

        verify(fixture.statement()).setString(1, "tenant-1");
        verify(fixture.statement()).setString(2, "false");
        verify(fixture.statement(), times(2)).execute();
        verify(fixture.connection()).close();

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(fixture.connection(), times(2)).prepareStatement(sql.capture());
        assertThat(sql.getAllValues()).containsExactly(
                TenantRlsDataSource.SET_SETTINGS_SQL,
                TenantRlsDataSource.RESET_SETTINGS_SQL);
    }

    @Test
    void withoutTenantContextTheConnectionRunsAsPlatform() throws Exception {
        Fixture fixture = new Fixture();
        try (Connection ignored = fixture.dataSource().getConnection()) {
            assertThat(ignored).isNotNull();
        }
        verify(fixture.statement()).setString(2, "true");
    }

    @Test
    void platformAdministratorAndMachineCallersBypassRls() throws Exception {
        Fixture admin = new Fixture();
        TenantContext.set(new TenantScope(null, "u-admin", Set.of("PLATFORM_ADMIN")));
        AuthContext.set(new Principal("u-admin", "admin", null, Set.of("PLATFORM_ADMIN"), "JWT"));
        try (Connection ignored = admin.dataSource().getConnection()) {
            assertThat(ignored).isNotNull();
        }
        verify(admin.statement()).setString(2, "true");

        TenantContext.clear();
        AuthContext.clear();
        Fixture machine = new Fixture();
        AuthContext.set(Principal.apiKey("dev-mcp-key"));
        try (Connection ignored = machine.dataSource().getConnection()) {
            assertThat(ignored).isNotNull();
        }
        verify(machine.statement()).setString(2, "true");
    }

    @Test
    void currentGucDerivesFromTheThreadContext() {
        TenantContext.set(new TenantScope("tenant-9", "u-9", Set.of("MEMBER")));
        AuthContext.set(new Principal("u-9", "member", "tenant-9", Set.of("MEMBER"), "JWT"));
        TenantRlsDataSource.Guc guc = TenantRlsDataSource.currentGuc();
        assertThat(guc.tenantId()).isEqualTo("tenant-9");
        assertThat(guc.platform()).isFalse();

        TenantContext.clear();
        TenantRlsDataSource.Guc platform = TenantRlsDataSource.currentGuc();
        assertThat(platform.tenantId()).isNull();
        assertThat(platform.platform()).isTrue();
    }

    /** Mocked Hikari-style DataSource with one physical connection. */
    private static final class Fixture {
        private final DataSource target = mock(DataSource.class);
        private final Connection connection = mock(Connection.class);
        private final PreparedStatement statement = mock(PreparedStatement.class);

        Fixture() throws Exception {
            when(target.getConnection()).thenReturn(connection);
            when(connection.prepareStatement(anyString())).thenReturn(statement);
            when(connection.isClosed()).thenReturn(false);
        }

        TenantRlsDataSource dataSource() {
            return new TenantRlsDataSource(target);
        }

        Connection connection() {
            return connection;
        }

        PreparedStatement statement() {
            return statement;
        }

        @SuppressWarnings("unused")
        List<String> executedSql() {
            return null;
        }
    }
}
