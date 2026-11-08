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

/** Read-only SCIM projections of {@code accounts} (writes reuse {@link com.suhong.jiuding.tenant.repository.AccountRepository}). */
@Repository
public class ScimDirectoryRepository {

    private static final RowMapper<ScimAccountRow> ACCOUNT_MAPPER = (rs, rowNum) -> new ScimAccountRow(
            rs.getString("id"),
            rs.getString("tenant_id"),
            rs.getString("org_unit_id"),
            rs.getString("username"),
            rs.getString("display_name"),
            rs.getString("email"),
            rs.getString("phone"),
            rs.getString("role"),
            rs.getString("status"),
            rs.getObject("created_at", LocalDateTime.class),
            rs.getObject("updated_at", LocalDateTime.class),
            rs.getLong("version"));

    private static final String ACCOUNT_COLUMNS = "id, tenant_id, org_unit_id, username, display_name,"
            + " email, phone, role, status, created_at, updated_at, version";

    private final JdbcTemplate jdbc;

    public ScimDirectoryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<ScimAccountRow> findAccount(String tenantId, String id) {
        List<ScimAccountRow> rows = jdbc.query("SELECT " + ACCOUNT_COLUMNS + " FROM accounts"
                + " WHERE tenant_id = ? AND id = ? AND deleted_at IS NULL", ACCOUNT_MAPPER, tenantId, id);
        return rows.stream().findFirst();
    }

    /** Includes soft deleted rows so SCIM userName uniqueness matches the DB constraint. */
    public Optional<ScimAccountRow> findAnyAccountByUsername(String tenantId, String username) {
        List<ScimAccountRow> rows = jdbc.query("SELECT " + ACCOUNT_COLUMNS + " FROM accounts"
                + " WHERE tenant_id = ? AND username = ?", ACCOUNT_MAPPER, tenantId, username);
        return rows.stream().findFirst();
    }

    public List<ScimAccountRow> listAccounts(String tenantId, String attribute, String value,
                                             long offset, int count) {
        List<Object> args = new ArrayList<>();
        String where = whereClause(tenantId, attribute, value, args);
        args.add(count);
        args.add(offset);
        return jdbc.query("SELECT " + ACCOUNT_COLUMNS + " FROM accounts" + where
                + " ORDER BY created_at ASC, username ASC LIMIT ? OFFSET ?", ACCOUNT_MAPPER, args.toArray());
    }

    public long countAccounts(String tenantId, String attribute, String value) {
        List<Object> args = new ArrayList<>();
        String where = whereClause(tenantId, attribute, value, args);
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM accounts" + where, Long.class, args.toArray());
        return total == null ? 0L : total;
    }

    /** Direct members of a SCIM group (accounts whose org_unit_id points at the group). */
    public List<ScimAccountRow> listMembers(String tenantId, String orgUnitId) {
        return jdbc.query("SELECT " + ACCOUNT_COLUMNS + " FROM accounts"
                        + " WHERE tenant_id = ? AND org_unit_id = ? AND deleted_at IS NULL"
                        + " ORDER BY username ASC",
                ACCOUNT_MAPPER, tenantId, orgUnitId);
    }

    /** Case-insensitive exact display-name match used by the SCIM group filter. */
    public List<ScimAccountRow> listAccountsByDisplayName(String tenantId, String displayName) {
        return jdbc.query("SELECT " + ACCOUNT_COLUMNS + " FROM accounts"
                        + " WHERE tenant_id = ? AND deleted_at IS NULL AND LOWER(display_name) = LOWER(?)"
                        + " ORDER BY username ASC",
                ACCOUNT_MAPPER, tenantId, displayName);
    }

    private String whereClause(String tenantId, String attribute, String value, List<Object> args) {
        StringBuilder where = new StringBuilder(" WHERE tenant_id = ? AND deleted_at IS NULL");
        args.add(tenantId);
        if (attribute == null) {
            return where.toString();
        }
        switch (attribute) {
            case "userName" -> {
                where.append(" AND LOWER(username) = LOWER(?)");
                args.add(value);
            }
            case "displayName" -> {
                where.append(" AND LOWER(display_name) = LOWER(?)");
                args.add(value);
            }
            case "active" -> {
                boolean active = Boolean.parseBoolean(value);
                where.append(active ? " AND status = 'ACTIVE'" : " AND status <> 'ACTIVE'");
            }
            default -> throw ScimException.badRequest("unsupported filter attribute: " + attribute);
        }
        return where.toString();
    }

    /** SCIM User projection of an account. */
    public record ScimAccountRow(
            String id,
            String tenantId,
            String orgUnitId,
            String username,
            String displayName,
            String email,
            String phone,
            String role,
            String status,
            LocalDateTime createdAt,
            LocalDateTime updatedAt,
            long version) {
    }
}
