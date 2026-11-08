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
package com.suhong.jiuding.mcp.external;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/** JdbcTemplate access to {@code mcp_external_servers}. */
@Repository
public class ExternalServerRepository {

    private static final RowMapper<ExternalServerRow> MAPPER = (rs, rowNum) -> new ExternalServerRow(
            rs.getString("id"),
            rs.getString("name"),
            rs.getString("transport"),
            rs.getString("url"),
            rs.getString("headers_json"),
            rs.getString("status"),
            rs.getString("created_by"),
            local(rs, "created_at"),
            local(rs, "last_check_at"),
            rs.getObject("last_check_ok") == null ? null : rs.getBoolean("last_check_ok"));

    private final JdbcTemplate jdbc;

    public ExternalServerRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<ExternalServerRow> list() {
        return jdbc.query("SELECT * FROM mcp_external_servers ORDER BY created_at DESC", MAPPER);
    }

    public Optional<ExternalServerRow> findById(String id) {
        List<ExternalServerRow> rows = jdbc.query("SELECT * FROM mcp_external_servers WHERE id = ?", MAPPER, id);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public void insert(ExternalServerRow row) {
        jdbc.update("INSERT INTO mcp_external_servers (id, name, transport, url, headers_json, status, "
                        + "created_by, created_at, last_check_at, last_check_ok) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                row.id(), row.name(), row.transport(), row.url(), row.headersJson(), row.status(),
                row.createdBy(), Timestamp.valueOf(row.createdAt()),
                row.lastCheckAt() == null ? null : Timestamp.valueOf(row.lastCheckAt()),
                row.lastCheckOk());
    }

    public void delete(String id) {
        jdbc.update("DELETE FROM mcp_external_servers WHERE id = ?", id);
    }

    public void updateCheck(String id, LocalDateTime checkedAt, boolean ok) {
        jdbc.update("UPDATE mcp_external_servers SET last_check_at = ?, last_check_ok = ? WHERE id = ?",
                Timestamp.valueOf(checkedAt), ok, id);
    }

    private static LocalDateTime local(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toLocalDateTime();
    }
}
