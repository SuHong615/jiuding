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

import java.util.List;
import java.util.Optional;

/** Pure JdbcTemplate persistence for the append-only {@code tenant_ratings} history. */
@Repository
public class RatingRepository {

    private static final String COLUMNS = "id, tenant_id, grade, score, dim_business, dim_compliance,"
            + " dim_financial, dim_security, dim_service, source, assessed_by, note, created_at";

    private static final RowMapper<RatingRow> ROW_MAPPER = (rs, rowNum) -> new RatingRow(
            rs.getString("id"),
            rs.getString("tenant_id"),
            rs.getString("grade"),
            rs.getInt("score"),
            rs.getInt("dim_business"),
            rs.getInt("dim_compliance"),
            rs.getInt("dim_financial"),
            rs.getInt("dim_security"),
            rs.getInt("dim_service"),
            rs.getString("source"),
            rs.getString("assessed_by"),
            rs.getString("note"),
            rs.getObject("created_at", java.time.LocalDateTime.class));

    private final JdbcTemplate jdbc;

    public RatingRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(RatingRow row) {
        jdbc.update("INSERT INTO tenant_ratings (" + COLUMNS + ") VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)",
                row.id(), row.tenantId(), row.grade(), row.score(), row.dimBusiness(),
                row.dimCompliance(), row.dimFinancial(), row.dimSecurity(), row.dimService(),
                row.source(), row.assessedBy(), row.note(), row.createdAt());
    }

    public List<RatingRow> history(String tenantId, int page, int size) {
        return historyFrom(tenantId, (long) page * size, size);
    }

    /** Offset based history query (descending). Used to compute deltas beyond page bounds. */
    public List<RatingRow> historyFrom(String tenantId, long offset, int limit) {
        return jdbc.query("SELECT " + COLUMNS + " FROM tenant_ratings WHERE tenant_id = ?"
                        + " ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?",
                ROW_MAPPER, tenantId, limit, offset);
    }

    public long count(String tenantId) {
        Long total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM tenant_ratings WHERE tenant_id = ?", Long.class, tenantId);
        return total == null ? 0L : total;
    }

    public Optional<RatingRow> latest(String tenantId) {
        List<RatingRow> rows = jdbc.query("SELECT " + COLUMNS + " FROM tenant_ratings WHERE tenant_id = ?"
                + " ORDER BY created_at DESC, id DESC LIMIT 1", ROW_MAPPER, tenantId);
        return rows.stream().findFirst();
    }
}
