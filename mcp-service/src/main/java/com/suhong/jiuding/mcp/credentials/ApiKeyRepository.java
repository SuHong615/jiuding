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
package com.suhong.jiuding.mcp.credentials;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/** JdbcTemplate access to {@code mcp_api_keys}. */
@Repository
public class ApiKeyRepository {

    private static final RowMapper<ApiKeyRow> MAPPER = (rs, rowNum) -> new ApiKeyRow(
            rs.getString("id"),
            rs.getString("name"),
            rs.getString("key_prefix"),
            rs.getString("key_hash"),
            rs.getString("owner_user_id"),
            rs.getString("owner_username"),
            rs.getString("tenant_id"),
            rs.getString("roles"),
            rs.getString("status"),
            local(rs, "created_at"),
            local(rs, "expires_at"),
            local(rs, "last_used_at"),
            local(rs, "revoked_at"));

    private final JdbcTemplate jdbc;

    public ApiKeyRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(ApiKeyRow row) {
        jdbc.update("INSERT INTO mcp_api_keys (id, name, key_prefix, key_hash, owner_user_id, "
                        + "owner_username, tenant_id, roles, status, created_at, expires_at, last_used_at, revoked_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                row.id(), row.name(), row.keyPrefix(), row.keyHash(), row.ownerUserId(),
                row.ownerUsername(), row.tenantId(), row.roles(), row.status(),
                Timestamp.valueOf(row.createdAt()), timestamp(row.expiresAt()),
                timestamp(row.lastUsedAt()), timestamp(row.revokedAt()));
    }

    public Optional<ApiKeyRow> findById(String id) {
        List<ApiKeyRow> rows = jdbc.query("SELECT * FROM mcp_api_keys WHERE id = ?", MAPPER, id);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public Optional<ApiKeyRow> findByHash(String keyHash) {
        List<ApiKeyRow> rows = jdbc.query("SELECT * FROM mcp_api_keys WHERE key_hash = ?", MAPPER, keyHash);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public List<ApiKeyRow> listByOwner(String ownerUserId) {
        return jdbc.query("SELECT * FROM mcp_api_keys WHERE owner_user_id = ? ORDER BY created_at DESC",
                MAPPER, ownerUserId);
    }

    public List<ApiKeyRow> listAll() {
        return jdbc.query("SELECT * FROM mcp_api_keys ORDER BY created_at DESC", MAPPER);
    }

    public int revoke(String id, LocalDateTime revokedAt) {
        return jdbc.update("UPDATE mcp_api_keys SET status = 'REVOKED', revoked_at = ? WHERE id = ? AND status = 'ACTIVE'",
                Timestamp.valueOf(revokedAt), id);
    }

    public void touchLastUsed(String id, LocalDateTime usedAt) {
        jdbc.update("UPDATE mcp_api_keys SET last_used_at = ? WHERE id = ?", Timestamp.valueOf(usedAt), id);
    }

    private static Timestamp timestamp(LocalDateTime value) {
        return value == null ? null : Timestamp.valueOf(value);
    }

    private static LocalDateTime local(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toLocalDateTime();
    }
}
