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

/** Persistence for the login security trail {@code account_login_events}. */
@Repository
public class LoginEventRepository {

    private static final String COLUMNS = "id, tenant_id, account_id, username, at, ip, user_agent, success, reason";

    private static final RowMapper<LoginEventRow> ROW_MAPPER = (rs, rowNum) -> new LoginEventRow(
            rs.getString("id"),
            rs.getString("tenant_id"),
            rs.getString("account_id"),
            rs.getString("username"),
            rs.getObject("at", java.time.LocalDateTime.class),
            rs.getString("ip"),
            rs.getString("user_agent"),
            rs.getBoolean("success"),
            rs.getString("reason"));

    private final JdbcTemplate jdbc;

    public LoginEventRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(LoginEventRow row) {
        jdbc.update("INSERT INTO account_login_events (" + COLUMNS + ") VALUES (?,?,?,?,?,?,?,?,?)",
                row.id(), row.tenantId(), row.accountId(), row.username(), row.at(), row.ip(),
                row.userAgent(), row.success(), row.reason());
    }

    public List<LoginEventRow> page(String tenantId, String accountId, int page, int size) {
        return jdbc.query("SELECT " + COLUMNS + " FROM account_login_events"
                        + " WHERE tenant_id = ? AND account_id = ?"
                        + " ORDER BY at DESC, id DESC LIMIT ? OFFSET ?",
                ROW_MAPPER, tenantId, accountId, size, (long) page * size);
    }

    public long count(String tenantId, String accountId) {
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM account_login_events"
                + " WHERE tenant_id = ? AND account_id = ?", Long.class, tenantId, accountId);
        return total == null ? 0L : total;
    }
}
