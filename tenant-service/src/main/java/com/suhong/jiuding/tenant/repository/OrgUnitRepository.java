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
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Pure JdbcTemplate persistence for the {@code org_units} materialised-path tree.
 *
 * <p>Every statement is tenant scoped ({@code tenant_id = ?}); callers are responsible for
 * resolving/validating the tenant scope before invoking these methods.
 */
@Repository
public class OrgUnitRepository {

    private static final List<String> COLUMN_NAMES = List.of(
            "id", "tenant_id", "parent_id", "code", "name", "type", "path", "depth",
            "sort_order", "manager_account_id", "status", "created_at", "updated_at", "version");

    private static final String COLUMNS = String.join(", ", COLUMN_NAMES);
    private static final String INSERT_SQL = "INSERT INTO org_units (" + COLUMNS + ") VALUES ("
            + String.join(", ", Collections.nCopies(COLUMN_NAMES.size(), "?")) + ")";

    private static final RowMapper<OrgUnitRow> ROW_MAPPER = (rs, rowNum) -> new OrgUnitRow(
            rs.getString("id"),
            rs.getString("tenant_id"),
            rs.getString("parent_id"),
            rs.getString("code"),
            rs.getString("name"),
            rs.getString("type"),
            rs.getString("path"),
            rs.getInt("depth"),
            rs.getInt("sort_order"),
            rs.getString("manager_account_id"),
            rs.getString("status"),
            rs.getObject("created_at", java.time.LocalDateTime.class),
            rs.getObject("updated_at", java.time.LocalDateTime.class),
            rs.getLong("version"));

    private final JdbcTemplate jdbc;

    public OrgUnitRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<OrgUnitRow> findById(String tenantId, String id) {
        List<OrgUnitRow> rows = jdbc.query(
                "SELECT " + COLUMNS + " FROM org_units WHERE tenant_id = ? AND id = ?",
                ROW_MAPPER, tenantId, id);
        return rows.stream().findFirst();
    }

    public Optional<OrgUnitRow> findByCode(String tenantId, String code) {
        List<OrgUnitRow> rows = jdbc.query(
                "SELECT " + COLUMNS + " FROM org_units WHERE tenant_id = ? AND code = ?",
                ROW_MAPPER, tenantId, code);
        return rows.stream().findFirst();
    }

    /** Flat list ordered by tree position (depth, sort order, creation time). */
    public List<OrgUnitRow> list(String tenantId, String status, String keyword) {
        List<Object> args = new ArrayList<>();
        StringBuilder where = new StringBuilder(" WHERE tenant_id = ?");
        args.add(tenantId);
        if (status != null) {
            where.append(" AND status = ?");
            args.add(status);
        }
        if (keyword != null) {
            String pattern = "%" + keyword.toLowerCase(Locale.ROOT) + "%";
            where.append(" AND (LOWER(code) LIKE ? OR LOWER(name) LIKE ?)");
            args.add(pattern);
            args.add(pattern);
        }
        return jdbc.query("SELECT " + COLUMNS + " FROM org_units" + where
                + " ORDER BY depth ASC, sort_order ASC, created_at ASC, code ASC", ROW_MAPPER, args.toArray());
    }

    /** Root-to-leaf subtree: the unit itself plus every descendant (path prefix match). */
    public List<OrgUnitRow> findSubtree(String tenantId, String path) {
        return jdbc.query("SELECT " + COLUMNS + " FROM org_units WHERE tenant_id = ?"
                        + " AND (path = ? OR path LIKE ?) ORDER BY depth ASC, sort_order ASC",
                ROW_MAPPER, tenantId, path, path + "/%");
    }

    public long countByTenant(String tenantId) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM org_units WHERE tenant_id = ?", Long.class, tenantId);
        return count == null ? 0L : count;
    }

    public long countChildren(String tenantId, String parentId) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM org_units WHERE tenant_id = ? AND parent_id = ?",
                Long.class, tenantId, parentId);
        return count == null ? 0L : count;
    }

    public int insert(OrgUnitRow row) {
        return jdbc.update(INSERT_SQL,
                row.id(), row.tenantId(), row.parentId(), row.code(), row.name(), row.type(),
                row.path(), row.depth(), row.sortOrder(), row.managerAccountId(), row.status(),
                row.createdAt(), row.updatedAt(), row.version());
    }

    /** Optimistic update of an org unit; @return rows changed (0 = version/id guard failed). */
    public int updateFields(String tenantId, String id, long expectedVersion, Map<String, Object> fields) {
        if (fields.isEmpty()) {
            return 1;
        }
        StringBuilder sql = new StringBuilder("UPDATE org_units SET ");
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

    /** Moves one row of the subtree (path/depth rewrite); version is bumped without a guard. */
    public int updateSubtreeRow(String tenantId, String id, String path, int depth, java.time.LocalDateTime updatedAt) {
        return jdbc.update("UPDATE org_units SET path = ?, depth = ?, updated_at = ?, version = version + 1"
                + " WHERE tenant_id = ? AND id = ?", path, depth, updatedAt, tenantId, id);
    }

    public int deleteById(String tenantId, String id) {
        return jdbc.update("DELETE FROM org_units WHERE tenant_id = ? AND id = ?", tenantId, id);
    }

    /**
     * Clears {@code manager_account_id} on every org unit that references a deleted account,
     * so the public tree projection can never expose a soft deleted account as a manager.
     *
     * @return number of org units whose manager reference was cleared
     */
    public int clearManagerReference(String tenantId, String accountId, java.time.LocalDateTime updatedAt) {
        return jdbc.update("UPDATE org_units SET manager_account_id = NULL, updated_at = ?,"
                        + " version = version + 1 WHERE tenant_id = ? AND manager_account_id = ?",
                updatedAt, tenantId, accountId);
    }

    public java.util.Map<String, String> namesByIds(String tenantId, java.util.Collection<String> ids) {
        java.util.Map<String, String> names = new java.util.LinkedHashMap<>();
        if (ids == null || ids.isEmpty()) {
            return names;
        }
        List<Object> args = new ArrayList<>();
        args.add(tenantId);
        args.addAll(ids);
        String placeholders = String.join(", ", Collections.nCopies(ids.size(), "?"));
        jdbc.query("SELECT id, name FROM org_units WHERE tenant_id = ? AND id IN (" + placeholders + ")",
                rs -> {
                    names.put(rs.getString("id"), rs.getString("name"));
                }, args.toArray());
        return names;
    }
}
