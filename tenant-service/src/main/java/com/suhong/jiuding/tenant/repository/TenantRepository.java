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
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Pure JdbcTemplate persistence for the {@code tenants} table. */
@Repository
public class TenantRepository {

    private static final List<String> COLUMN_NAMES = List.of(
            "id", "tenant_code", "name", "contact_name", "contact_email", "contact_phone",
            "plan", "region", "isolation_mode", "status", "risk_level", "risk_score",
            "quota_seats", "quota_storage_gb", "quota_api_rpm", "source", "attributes_json",
            "reject_reason", "created_at", "updated_at", "submitted_at", "decided_at",
            "activated_at", "rating_grade", "rating_score", "rating_assessed_at", "deleted_at",
            "version");

    private static final String COLUMNS = String.join(", ", COLUMN_NAMES);
    private static final String INSERT_SQL = "INSERT INTO tenants (" + COLUMNS + ") VALUES ("
            + String.join(", ", Collections.nCopies(COLUMN_NAMES.size(), "?")) + ")";

    private static final RowMapper<TenantRow> ROW_MAPPER = (rs, rowNum) -> new TenantRow(
            rs.getString("id"),
            rs.getString("tenant_code"),
            rs.getString("name"),
            rs.getString("contact_name"),
            rs.getString("contact_email"),
            rs.getString("contact_phone"),
            rs.getString("plan"),
            rs.getString("region"),
            rs.getString("isolation_mode"),
            rs.getString("status"),
            rs.getString("risk_level"),
            (Integer) rs.getObject("risk_score"),
            (Integer) rs.getObject("quota_seats"),
            (Integer) rs.getObject("quota_storage_gb"),
            (Integer) rs.getObject("quota_api_rpm"),
            rs.getString("source"),
            rs.getString("attributes_json"),
            rs.getString("reject_reason"),
            rs.getObject("created_at", LocalDateTime.class),
            rs.getObject("updated_at", LocalDateTime.class),
            rs.getObject("submitted_at", LocalDateTime.class),
            rs.getObject("decided_at", LocalDateTime.class),
            rs.getObject("activated_at", LocalDateTime.class),
            rs.getString("rating_grade"),
            (Integer) rs.getObject("rating_score"),
            rs.getObject("rating_assessed_at", LocalDateTime.class),
            rs.getObject("deleted_at", LocalDateTime.class),
            rs.getLong("version"));

    private static final Set<String> GROUPABLE_COLUMNS = Set.of("status", "plan", "risk_level");

    private final JdbcTemplate jdbc;

    public TenantRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<TenantRow> findById(String id) {
        List<TenantRow> rows = jdbc.query(
                "SELECT " + COLUMNS + " FROM tenants WHERE id = ?", ROW_MAPPER, id);
        return rows.stream().findFirst();
    }

    public Optional<TenantRow> findByCode(String tenantCode) {
        List<TenantRow> rows = jdbc.query(
                "SELECT " + COLUMNS + " FROM tenants WHERE tenant_code = ?", ROW_MAPPER, tenantCode);
        return rows.stream().findFirst();
    }

    /** ACTIVE, not soft deleted tenants (rating refresh batch, docs/13 section 4). */
    public List<TenantRow> findActive(int limit) {
        return jdbc.query("SELECT " + COLUMNS + " FROM tenants"
                        + " WHERE status = 'ACTIVE' AND deleted_at IS NULL ORDER BY id LIMIT ?",
                ROW_MAPPER, limit);
    }

    /** All not soft deleted tenants (used to report skipped rows of a refresh batch). */
    public long countNonDeleted() {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM tenants WHERE deleted_at IS NULL", Long.class);
        return count == null ? 0L : count;
    }

    public void insert(TenantRow row) {
        jdbc.update(INSERT_SQL,
                row.id(), row.tenantCode(), row.name(), row.contactName(), row.contactEmail(),
                row.contactPhone(), row.plan(), row.region(), row.isolationMode(), row.status(),
                row.riskLevel(), row.riskScore(), row.quotaSeats(), row.quotaStorageGb(),
                row.quotaApiRpm(), row.source(), row.attributesJson(), row.rejectReason(),
                row.createdAt(), row.updatedAt(), row.submittedAt(), row.decidedAt(),
                row.activatedAt(), row.ratingGrade(), row.ratingScore(), row.ratingAssessedAt(),
                row.deletedAt(), row.version());
    }

    /**
     * Optimistic update using an explicit column map (column names are internal constants).
     *
     * @return number of rows changed (0 means the version / id guard failed)
     */
    public int updateFields(String id, long expectedVersion, Map<String, Object> fields) {
        if (fields.isEmpty()) {
            return 1;
        }
        StringBuilder sql = new StringBuilder("UPDATE tenants SET ");
        List<Object> args = new ArrayList<>();
        for (Map.Entry<String, Object> entry : fields.entrySet()) {
            sql.append(entry.getKey()).append(" = ?, ");
            args.add(entry.getValue());
        }
        sql.append("version = version + 1 WHERE id = ? AND version = ?");
        args.add(id);
        args.add(expectedVersion);
        return jdbc.update(sql.toString(), args.toArray());
    }

    public List<TenantRow> search(String scopeTenantId, TenantQuery query, int page, int size) {
        List<Object> args = new ArrayList<>();
        String where = whereClause(scopeTenantId, query, args);
        args.add(size);
        args.add((long) page * size);
        return jdbc.query("SELECT " + COLUMNS + " FROM tenants" + where
                + " ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?", ROW_MAPPER, args.toArray());
    }

    public long count(String scopeTenantId, TenantQuery query) {
        List<Object> args = new ArrayList<>();
        String where = whereClause(scopeTenantId, query, args);
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM tenants" + where, Long.class, args.toArray());
        return total == null ? 0L : total;
    }

    /** Count rows grouped by a whitelisted column; rows with a NULL group key are labelled UNKNOWN. */
    public Map<String, Long> countBy(String column, String scopeTenantId) {
        if (!GROUPABLE_COLUMNS.contains(column)) {
            throw new IllegalArgumentException("unsupported group column: " + column);
        }
        List<Object> args = new ArrayList<>();
        String where = " WHERE deleted_at IS NULL";
        if (scopeTenantId != null) {
            where += " AND (tenant_code = ? OR id = ?)";
            args.add(scopeTenantId);
            args.add(scopeTenantId);
        }
        Map<String, Long> counts = new LinkedHashMap<>();
        RowCallbackHandler handler = rs -> {
            String key = rs.getString("k");
            counts.put(key == null ? "UNKNOWN" : key, rs.getLong("c"));
        };
        jdbc.query("SELECT " + column + " AS k, COUNT(*) AS c FROM tenants" + where
                + " GROUP BY " + column + " ORDER BY " + column, handler, args.toArray());
        return counts;
    }

    private String whereClause(String scopeTenantId, TenantQuery query, List<Object> args) {
        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        if (query != null) {
            if (!query.includeDeleted()) {
                where.append(" AND deleted_at IS NULL");
            }
            if (query.status() != null) {
                where.append(" AND status = ?");
                args.add(query.status());
            }
            if (query.plan() != null) {
                where.append(" AND plan = ?");
                args.add(query.plan());
            }
            if (query.keyword() != null) {
                String keyword = "%" + query.keyword().toLowerCase(Locale.ROOT) + "%";
                where.append(" AND (LOWER(tenant_code) LIKE ? OR LOWER(name) LIKE ?"
                        + " OR LOWER(COALESCE(contact_email, '')) LIKE ?)");
                args.add(keyword);
                args.add(keyword);
                args.add(keyword);
            }
        }
        if (scopeTenantId != null) {
            where.append(" AND (tenant_code = ? OR id = ?)");
            args.add(scopeTenantId);
            args.add(scopeTenantId);
        }
        return where.toString();
    }
}
