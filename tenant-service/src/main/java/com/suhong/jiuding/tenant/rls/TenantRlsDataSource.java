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
import org.springframework.jdbc.datasource.DelegatingDataSource;

import javax.sql.DataSource;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

/**
 * DataSource wrapper that injects the PostgreSQL RLS connection settings (docs/23 section 2).
 *
 * <p>Every borrowed connection gets {@code app.tenant_id} / {@code app.platform} from the
 * thread-bound {@link TenantContext} / {@link AuthContext}; the values are reset before the
 * physical connection returns to the pool. Requests without a tenant scope (internal calls,
 * schedulers, public onboarding) run as {@code app.platform=true}; platform administrators and
 * trusted machine callers also bypass RLS.
 */
public class TenantRlsDataSource extends DelegatingDataSource {

    static final String SET_SETTINGS_SQL =
            "SELECT set_config('app.tenant_id', ?, true), set_config('app.platform', ?, true)";
    static final String RESET_SETTINGS_SQL =
            "SELECT set_config('app.tenant_id', '', true), set_config('app.platform', 'false', true)";

    public TenantRlsDataSource(DataSource targetDataSource) {
        super(targetDataSource);
    }

    @Override
    public Connection getConnection() throws SQLException {
        return configure(super.getConnection());
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return configure(super.getConnection(username, password));
    }

    private Connection configure(Connection connection) throws SQLException {
        Guc guc = currentGuc();
        try (PreparedStatement statement = connection.prepareStatement(SET_SETTINGS_SQL)) {
            statement.setString(1, guc.tenantId());
            statement.setString(2, guc.platform() ? "true" : "false");
            statement.execute();
        } catch (SQLException ex) {
            try {
                connection.close();
            } catch (SQLException ignored) {
                // keep the original failure
            }
            throw ex;
        }
        return (Connection) Proxy.newProxyInstance(
                TenantRlsDataSource.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                new RlsConnectionHandler(connection));
    }

    /** Current connection GUC values derived from the tenant/auth context. */
    static Guc currentGuc() {
        TenantScope scope = TenantContext.get();
        Principal principal = AuthContext.get();
        String tenantId = scope == null ? null : scope.tenantId();
        if (tenantId != null && tenantId.isBlank()) {
            tenantId = null;
        }
        boolean platform = false;
        if (principal != null && ("API_KEY".equals(principal.authType())
                || "INTERNAL_TOKEN".equals(principal.authType())
                || principal.isPlatformAdmin())) {
            platform = true;
        } else if (tenantId == null) {
            // Internal calls, schedulers and public onboarding have no tenant scope.
            platform = true;
        }
        return new Guc(tenantId, platform);
    }

    /** Invocation handler resetting the GUCs before the physical connection is closed. */
    private static final class RlsConnectionHandler implements InvocationHandler {

        private final Connection target;

        RlsConnectionHandler(Connection target) {
            this.target = target;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            String name = method.getName();
            if ("close".equals(name)) {
                resetSettings();
                target.close();
                return null;
            }
            try {
                return method.invoke(target, args);
            } catch (InvocationTargetException ex) {
                throw ex.getTargetException();
            }
        }

        private void resetSettings() {
            try {
                if (target.isClosed()) {
                    return;
                }
            } catch (SQLException ignored) {
                return;
            }
            try (PreparedStatement statement = target.prepareStatement(RESET_SETTINGS_SQL)) {
                statement.execute();
            } catch (SQLException ignored) {
                // The pool will roll the session back; never mask the caller's close().
            }
        }
    }

    record Guc(String tenantId, boolean platform) {
    }
}
