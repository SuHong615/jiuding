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

import com.suhong.jiuding.common.api.PageResult;
import com.suhong.jiuding.mcp.support.Iso8601;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/** JdbcTemplate access to {@code mcp_call_audits}. */
@Repository
public class McpCallAuditRepository {

    private static final RowMapper<McpCallAuditRow> MAPPER = (rs, rowNum) -> new McpCallAuditRow(
            rs.getString("id"),
            rs.getString("api_key_id"),
            rs.getString("key_prefix"),
            rs.getString("owner_username"),
            rs.getString("tenant_id"),
            rs.getString("tool"),
            rs.getString("request_id"),
            rs.getString("session_id"),
            rs.getBoolean("success"),
            rs.getObject("error_code") == null ? null : rs.getInt("error_code"),
            rs.getLong("duration_ms"),
            rs.getString("ip"),
            rs.getString("user_agent"),
            local(rs, "at"));

    private final JdbcTemplate jdbc;

    public McpCallAuditRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(McpCallAuditRow row) {
        jdbc.update("INSERT INTO mcp_call_audits (id, api_key_id, key_prefix, owner_username, tenant_id, "
                        + "tool, request_id, session_id, success, error_code, duration_ms, ip, user_agent, at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                row.id(), row.apiKeyId(), row.keyPrefix(), row.ownerUsername(), row.tenantId(),
                row.tool(), row.requestId(), row.sessionId(), row.success(), row.errorCode(),
                row.durationMs(), row.ip(), row.userAgent(), Timestamp.valueOf(row.at()));
    }

    /** @param ownerUsername when non-null restricts the page to that owner (non-admin callers) */
    public PageResult<AuditView> search(String ownerUsername, String tool, Boolean success, int page, int size) {
        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        List<Object> args = new ArrayList<>();
        if (ownerUsername != null) {
            where.append(" AND owner_username = ?");
            args.add(ownerUsername);
        }
        if (tool != null && !tool.isBlank()) {
            where.append(" AND tool = ?");
            args.add(tool);
        }
        if (success != null) {
            where.append(" AND success = ?");
            args.add(success);
        }
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM mcp_call_audits" + where,
                Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(size);
        pageArgs.add(page * size);
        List<AuditView> items = jdbc.query(
                "SELECT * FROM mcp_call_audits" + where + " ORDER BY at DESC LIMIT ? OFFSET ?",
                (rs, rowNum) -> AuditView.of(MAPPER.mapRow(rs, rowNum)),
                pageArgs.toArray());
        return PageResult.of(items, total == null ? 0 : total, page, size);
    }

    /** Public projection used by {@code GET /api/mcp/audits}. */
    public record AuditView(String id,
                            String apiKeyId,
                            String keyPrefix,
                            String ownerUsername,
                            String tenantId,
                            String tool,
                            String requestId,
                            String sessionId,
                            boolean success,
                            Integer errorCode,
                            long durationMs,
                            String ip,
                            String userAgent,
                            String at) {
        public static AuditView of(McpCallAuditRow row) {
            return new AuditView(row.id(), row.apiKeyId(), row.keyPrefix(), row.ownerUsername(),
                    row.tenantId(), row.tool(), row.requestId(), row.sessionId(), row.success(),
                    row.errorCode(), row.durationMs(), row.ip(), row.userAgent(), Iso8601.format(row.at()));
        }
    }

    private static LocalDateTime local(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toLocalDateTime();
    }
}
