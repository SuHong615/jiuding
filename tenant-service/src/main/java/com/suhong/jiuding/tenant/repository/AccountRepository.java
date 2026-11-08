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
package com.suhong.jiuding.tenant.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Pure JdbcTemplate persistence for the {@code accounts} directory.
 *
 * <p>All statements are tenant scoped; soft deleted rows ({@code deleted_at IS NOT NULL})
 * are invisible to the list/detail queries used by the public API.
 */
@Repository
public class AccountRepository {

    private static final List<String> COLUMN_NAMES = List.of(
            "id", "tenant_id", "org_unit_id", "username", "display_name", "email", "phone",
            "role", "status", "password_hash", "password_salt", "must_change_password",
            "failed_attempts", "last_login_at", "created_by", "created_at", "updated_at",
            "deleted_at", "version");

    private static final String COLUMNS = String.join(", ", COLUMN_NAMES);
    private static final String INSERT_SQL = "INSERT INTO accounts (" + COLUMNS + ") VALUES ("
            + String.join(", ", Collections.nCopies(COLUMN_NAMES.size(), "?")) + ")";

    private static final RowMapper<AccountRow> ROW_MAPPER = (rs, rowNum) -> new AccountRow(
            rs.getString("id"),
            rs.getString("tenant_id"),
            rs.getString("org_unit_id"),
            rs.getString("username"),
            rs.getString("display_name"),
            rs.getString("email"),
            rs.getString("phone"),
            rs.getString("role"),
            rs.getString("status"),
            rs.getString("password_hash"),
            rs.getString("password_salt"),
            rs.getBoolean("must_change_password"),
            rs.getInt("failed_attempts"),
            rs.getObject("last_login_at", java.time.LocalDateTime.class),
            rs.getString("created_by"),
            rs.getObject("created_at", java.time.LocalDateTime.class),
            rs.getObject("updated_at", java.time.LocalDateTime.class),
            rs.getObject("deleted_at", java.time.LocalDateTime.class),
            rs.getLong("version"));

    private final JdbcTemplate jdbc;

    public AccountRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<AccountRow> findById(String tenantId, String id) {
        List<AccountRow> rows = jdbc.query(
                "SELECT " + COLUMNS + " FROM accounts WHERE tenant_id = ? AND id = ? AND deleted_at IS NULL",
                ROW_MAPPER, tenantId, id);
        return rows.stream().findFirst();
    }

    /** Includes soft deleted rows so username uniqueness behaves like the DB constraint. */
    public Optional<AccountRow> findByUsername(String tenantId, String username) {
        List<AccountRow> rows = jdbc.query(
                "SELECT " + COLUMNS + " FROM accounts WHERE tenant_id = ? AND username = ?",
                ROW_MAPPER, tenantId, username);
        return rows.stream().findFirst();
    }

    public List<AccountRow> search(String tenantId, String orgUnitId, String role, String status,
                                   String keyword, int page, int size) {
        List<Object> args = new ArrayList<>();
        String where = whereClause(tenantId, orgUnitId, role, status, keyword, args);
        args.add(size);
        args.add((long) page * size);
        return jdbc.query("SELECT " + COLUMNS + " FROM accounts" + where
                + " ORDER BY created_at ASC, username ASC LIMIT ? OFFSET ?", ROW_MAPPER, args.toArray());
    }

    public long count(String tenantId, String orgUnitId, String role, String status, String keyword) {
        List<Object> args = new ArrayList<>();
        String where = whereClause(tenantId, orgUnitId, role, status, keyword, args);
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM accounts" + where, Long.class, args.toArray());
        return total == null ? 0L : total;
    }

    /** ORG_ADMIN scope: accounts whose org unit is inside the given materialised path subtree. */
    public List<AccountRow> searchInOrgSubtree(String tenantId, String path, String role, String status,
                                               String keyword, int page, int size) {
        List<Object> args = new ArrayList<>();
        String where = whereClause(tenantId, null, role, status, keyword, args)
                + orgSubtreeClause(tenantId, path, args);
        args.add(size);
        args.add((long) page * size);
        return jdbc.query("SELECT " + COLUMNS + " FROM accounts" + where
                + " ORDER BY created_at ASC, username ASC LIMIT ? OFFSET ?", ROW_MAPPER, args.toArray());
    }

    public long countInOrgSubtree(String tenantId, String path, String role, String status, String keyword) {
        List<Object> args = new ArrayList<>();
        String where = whereClause(tenantId, null, role, status, keyword, args)
                + orgSubtreeClause(tenantId, path, args);
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM accounts" + where, Long.class, args.toArray());
        return total == null ? 0L : total;
    }

    private String orgSubtreeClause(String tenantId, String path, List<Object> args) {
        args.add(tenantId);
        args.add(path);
        args.add(path + "/%");
        return " AND org_unit_id IN (SELECT id FROM org_units WHERE tenant_id = ?"
                + " AND (path = ? OR path LIKE ?))";
    }

    public long countByTenant(String tenantId) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM accounts WHERE tenant_id = ? AND deleted_at IS NULL", Long.class, tenantId);
        return count == null ? 0L : count;
    }

    public long countByOrgUnit(String tenantId, String orgUnitId) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM accounts WHERE tenant_id = ? AND org_unit_id = ?"
                + " AND deleted_at IS NULL", Long.class, tenantId, orgUnitId);
        return count == null ? 0L : count;
    }

    /** Account count of an org unit subtree (the unit itself plus all descendants). */
    public long countByOrgUnitSubtree(String tenantId, String path) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM accounts a"
                        + " JOIN org_units o ON o.id = a.org_unit_id AND o.tenant_id = a.tenant_id"
                        + " WHERE a.tenant_id = ? AND a.deleted_at IS NULL"
                        + " AND (o.path = ? OR o.path LIKE ?)",
                Long.class, tenantId, path, path + "/%");
        return count == null ? 0L : count;
    }

    /** Direct account counts grouped by org unit id (used by the tree/list projection). */
    public Map<String, Long> countGroupedByOrgUnit(String tenantId) {
        Map<String, Long> counts = new LinkedHashMap<>();
        jdbc.query("SELECT org_unit_id AS k, COUNT(*) AS c FROM accounts"
                        + " WHERE tenant_id = ? AND deleted_at IS NULL AND org_unit_id IS NOT NULL"
                        + " GROUP BY org_unit_id",
                rs -> {
                    counts.put(rs.getString("k"), rs.getLong("c"));
                }, tenantId);
        return counts;
    }

    public Map<String, String> displayNames(String tenantId, Collection<String> ids) {
        Map<String, String> names = new LinkedHashMap<>();
        if (ids == null || ids.isEmpty()) {
            return names;
        }
        List<Object> args = new ArrayList<>();
        args.add(tenantId);
        args.addAll(ids);
        String placeholders = String.join(", ", Collections.nCopies(ids.size(), "?"));
        jdbc.query("SELECT id, display_name FROM accounts WHERE tenant_id = ? AND id IN (" + placeholders + ")",
                rs -> {
                    names.put(rs.getString("id"), rs.getString("display_name"));
                }, args.toArray());
        return names;
    }

    public int insert(AccountRow row) {
        return jdbc.update(INSERT_SQL,
                row.id(), row.tenantId(), row.orgUnitId(), row.username(), row.displayName(),
                row.email(), row.phone(), row.role(), row.status(), row.passwordHash(),
                row.passwordSalt(), row.mustChangePassword(), row.failedAttempts(), row.lastLoginAt(),
                row.createdBy(), row.createdAt(), row.updatedAt(), row.deletedAt(), row.version());
    }

    /** Optimistic update; @return rows changed (0 = version/id guard failed). */
    public int updateFields(String tenantId, String id, long expectedVersion, Map<String, Object> fields) {
        if (fields.isEmpty()) {
            return 1;
        }
        StringBuilder sql = new StringBuilder("UPDATE accounts SET ");
        List<Object> args = new ArrayList<>();
        for (Map.Entry<String, Object> entry : fields.entrySet()) {
            sql.append(entry.getKey()).append(" = ?, ");
            args.add(entry.getValue());
        }
        sql.append("version = version + 1 WHERE tenant_id = ? AND id = ? AND version = ?");
        args.add(tenantId);
        args.add(id);
        args.add(expectedVersion);
        return jdbc.update(sql.toString(), args.toArray());
    }

    private String whereClause(String tenantId, String orgUnitId, String role, String status,
                               String keyword, List<Object> args) {
        StringBuilder where = new StringBuilder(" WHERE tenant_id = ? AND deleted_at IS NULL");
        args.add(tenantId);
        if (orgUnitId != null) {
            where.append(" AND org_unit_id = ?");
            args.add(orgUnitId);
        }
        if (role != null) {
            where.append(" AND role = ?");
            args.add(role);
        }
        if (status != null) {
            where.append(" AND status = ?");
            args.add(status);
        }
        if (keyword != null) {
            String pattern = "%" + keyword.toLowerCase(Locale.ROOT) + "%";
            where.append(" AND (LOWER(username) LIKE ? OR LOWER(display_name) LIKE ?"
                    + " OR LOWER(COALESCE(email, '')) LIKE ?)");
            args.add(pattern);
            args.add(pattern);
            args.add(pattern);
        }
        return where.toString();
    }
}
