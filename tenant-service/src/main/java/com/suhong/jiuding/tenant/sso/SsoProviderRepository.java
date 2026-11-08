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

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Pure JdbcTemplate persistence for the {@code sso_providers} table (docs/20 section 2). */
@Repository
public class SsoProviderRepository {

    private static final Set<String> COLUMNS = Set.of(
            "id", "code", "name", "issuer", "client_id", "client_secret", "scopes",
            "redirect_uri", "frontend_redirect", "role_claim", "default_role", "enabled",
            "created_at", "updated_at");

    private static final RowMapper<SsoProviderRow> ROW_MAPPER = (rs, rowNum) -> new SsoProviderRow(
            rs.getString("id"),
            rs.getString("code"),
            rs.getString("name"),
            rs.getString("issuer"),
            rs.getString("client_id"),
            rs.getString("client_secret"),
            rs.getString("scopes"),
            rs.getString("redirect_uri"),
            rs.getString("frontend_redirect"),
            rs.getString("role_claim"),
            rs.getString("default_role"),
            rs.getBoolean("enabled"),
            rs.getObject("created_at", LocalDateTime.class),
            rs.getObject("updated_at", LocalDateTime.class));

    private final JdbcTemplate jdbc;

    public SsoProviderRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<SsoProviderRow> list() {
        return jdbc.query("SELECT * FROM sso_providers ORDER BY created_at ASC, code ASC",
                ROW_MAPPER);
    }

    public List<SsoProviderRow> listEnabled() {
        return jdbc.query("SELECT * FROM sso_providers WHERE enabled = TRUE"
                + " ORDER BY created_at ASC, code ASC", ROW_MAPPER);
    }

    public Optional<SsoProviderRow> findById(String id) {
        return jdbc.query("SELECT * FROM sso_providers WHERE id = ?", ROW_MAPPER, id)
                .stream().findFirst();
    }

    public Optional<SsoProviderRow> findByCode(String code) {
        return jdbc.query("SELECT * FROM sso_providers WHERE code = ?", ROW_MAPPER, code)
                .stream().findFirst();
    }

    public void insert(SsoProviderRow row) {
        jdbc.update("INSERT INTO sso_providers (id, code, name, issuer, client_id, client_secret,"
                        + " scopes, redirect_uri, frontend_redirect, role_claim, default_role,"
                        + " enabled, created_at, updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                row.id(), row.code(), row.name(), row.issuer(), row.clientId(),
                row.clientSecret(), row.scopes(), row.redirectUri(), row.frontendRedirect(),
                row.roleClaim(), row.defaultRole(), row.enabled(), row.createdAt(), row.updatedAt());
    }

    public int updateFields(String id, Map<String, Object> fields) {
        if (fields.isEmpty()) {
            return 0;
        }
        StringBuilder sql = new StringBuilder("UPDATE sso_providers SET ");
        List<Object> args = new ArrayList<>();
        boolean first = true;
        for (Map.Entry<String, Object> entry : fields.entrySet()) {
            if (!COLUMNS.contains(entry.getKey())) {
                throw new IllegalArgumentException("unknown sso_providers column: " + entry.getKey());
            }
            if (!first) {
                sql.append(", ");
            }
            sql.append(entry.getKey()).append(" = ?");
            args.add(entry.getValue());
            first = false;
        }
        sql.append(" WHERE id = ?");
        args.add(id);
        return jdbc.update(sql.toString(), args.toArray());
    }

    public int delete(String id) {
        return jdbc.update("DELETE FROM sso_providers WHERE id = ?", id);
    }
}
