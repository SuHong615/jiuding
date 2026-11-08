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

/** Persistence for the tenant audit timeline ({@code tenant_audits}). */
@Repository
public class AuditRepository {

    private static final RowMapper<AuditRow> ROW_MAPPER = (rs, rowNum) -> new AuditRow(
            rs.getString("id"),
            rs.getString("tenant_id"),
            rs.getObject("at", java.time.LocalDateTime.class),
            rs.getString("actor"),
            rs.getString("action"),
            rs.getString("detail"));

    private final JdbcTemplate jdbc;

    public AuditRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(AuditRow row) {
        jdbc.update("INSERT INTO tenant_audits (id, tenant_id, at, actor, action, detail)"
                        + " VALUES (?, ?, ?, ?, ?, ?)",
                row.id(), row.tenantId(), row.at(), row.actor(), row.action(), row.detail());
    }

    public List<AuditRow> findByTenant(String tenantId, int limit) {
        return jdbc.query("SELECT id, tenant_id, at, actor, action, detail FROM tenant_audits"
                        + " WHERE tenant_id = ? ORDER BY at ASC, id ASC LIMIT ?",
                ROW_MAPPER, tenantId, limit);
    }

    /** Time range query used by the compliance CSV export (ascending, capped by the caller). */
    public List<AuditRow> findRange(String tenantId, java.time.LocalDateTime from,
                                    java.time.LocalDateTime to, int limit) {
        return jdbc.query("SELECT id, tenant_id, at, actor, action, detail FROM tenant_audits"
                        + " WHERE tenant_id = ? AND at >= ? AND at <= ?"
                        + " ORDER BY at ASC, id ASC LIMIT ?",
                ROW_MAPPER, tenantId, from, to, limit);
    }
}
