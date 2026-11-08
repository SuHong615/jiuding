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
package com.suhong.jiuding.tenant.scim;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Pure JdbcTemplate persistence for {@code scim_tokens} (docs/16 section 7.1). */
@Repository
public class ScimTokenRepository {

    private static final String COLUMNS = "id, tenant_id, name, token_prefix, token_hash, status,"
            + " created_by, created_at, last_used_at, revoked_at";

    private static final RowMapper<ScimTokenRow> ROW_MAPPER = (rs, rowNum) -> new ScimTokenRow(
            rs.getString("id"),
            rs.getString("tenant_id"),
            rs.getString("name"),
            rs.getString("token_prefix"),
            rs.getString("token_hash"),
            rs.getString("status"),
            rs.getString("created_by"),
            rs.getObject("created_at", LocalDateTime.class),
            rs.getObject("last_used_at", LocalDateTime.class),
            rs.getObject("revoked_at", LocalDateTime.class));

    private final JdbcTemplate jdbc;

    public ScimTokenRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(ScimTokenRow row) {
        jdbc.update("INSERT INTO scim_tokens (" + COLUMNS + ") VALUES (?,?,?,?,?,?,?,?,?,?)",
                row.id(), row.tenantId(), row.name(), row.tokenPrefix(), row.tokenHash(),
                row.status(), row.createdBy(), row.createdAt(), row.lastUsedAt(), row.revokedAt());
    }

    public Optional<ScimTokenRow> findById(String id) {
        List<ScimTokenRow> rows = jdbc.query("SELECT " + COLUMNS + " FROM scim_tokens WHERE id = ?",
                ROW_MAPPER, id);
        return rows.stream().findFirst();
    }

    public List<ScimTokenRow> listByTenant(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            return jdbc.query("SELECT " + COLUMNS + " FROM scim_tokens ORDER BY created_at DESC",
                    ROW_MAPPER);
        }
        return jdbc.query("SELECT " + COLUMNS + " FROM scim_tokens WHERE tenant_id = ?"
                + " ORDER BY created_at DESC", ROW_MAPPER, tenantId);
    }

    public Optional<ScimTokenRow> findActiveByHash(String tokenHash) {
        List<ScimTokenRow> rows = jdbc.query("SELECT " + COLUMNS + " FROM scim_tokens"
                + " WHERE token_hash = ? AND status = 'ACTIVE'", ROW_MAPPER, tokenHash);
        return rows.stream().findFirst();
    }

    public void updateLastUsed(String id, LocalDateTime usedAt) {
        jdbc.update("UPDATE scim_tokens SET last_used_at = ? WHERE id = ?", usedAt, id);
    }

    public void revoke(String id, LocalDateTime revokedAt) {
        jdbc.update("UPDATE scim_tokens SET status = 'REVOKED', revoked_at = ? WHERE id = ?",
                revokedAt, id);
    }

    /** Convenience for tests/diagnostics: every token of a tenant. */
    public List<String> listHashes(String tenantId) {
        List<String> hashes = new ArrayList<>();
        for (ScimTokenRow row : listByTenant(tenantId)) {
            hashes.add(row.tokenHash());
        }
        return hashes;
    }
}
