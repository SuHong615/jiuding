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
package com.suhong.jiuding.tenant.platformuser;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Pure JdbcTemplate persistence for the {@code platform_users} table (docs/20 section 2). */
@Repository
public class PlatformUserRepository {

    private static final Set<String> COLUMNS = Set.of(
            "id", "username", "display_name", "password_hash", "roles", "status", "email",
            "created_at", "updated_at", "last_login_at");

    private static final RowMapper<PlatformUserRow> ROW_MAPPER = (rs, rowNum) -> new PlatformUserRow(
            rs.getString("id"),
            rs.getString("username"),
            rs.getString("display_name"),
            rs.getString("password_hash"),
            rs.getString("roles"),
            rs.getString("status"),
            rs.getString("email"),
            rs.getObject("created_at", LocalDateTime.class),
            rs.getObject("updated_at", LocalDateTime.class),
            rs.getObject("last_login_at", LocalDateTime.class));

    private final JdbcTemplate jdbc;

    public PlatformUserRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<PlatformUserRow> list(String status, String keyword) {
        StringBuilder sql = new StringBuilder("SELECT * FROM platform_users WHERE 1 = 1");
        List<Object> args = new ArrayList<>();
        if (status != null && !status.isBlank()) {
            sql.append(" AND status = ?");
            args.add(status);
        }
        if (keyword != null && !keyword.isBlank()) {
            sql.append(" AND (LOWER(username) LIKE ? OR LOWER(display_name) LIKE ?)");
            String pattern = "%" + keyword.trim().toLowerCase() + "%";
            args.add(pattern);
            args.add(pattern);
        }
        sql.append(" ORDER BY created_at ASC, username ASC");
        return jdbc.query(sql.toString(), ROW_MAPPER, args.toArray());
    }

    public Optional<PlatformUserRow> findById(String id) {
        return jdbc.query("SELECT * FROM platform_users WHERE id = ?", ROW_MAPPER, id)
                .stream().findFirst();
    }

    public Optional<PlatformUserRow> findByUsername(String username) {
        return jdbc.query("SELECT * FROM platform_users WHERE username = ?", ROW_MAPPER, username)
                .stream().findFirst();
    }

    public void insert(PlatformUserRow row) {
        jdbc.update("INSERT INTO platform_users (id, username, display_name, password_hash,"
                        + " roles, status, email, created_at, updated_at, last_login_at)"
                        + " VALUES (?,?,?,?,?,?,?,?,?,?)",
                row.id(), row.username(), row.displayName(), row.passwordHash(), row.roles(),
                row.status(), row.email(), row.createdAt(), row.updatedAt(), row.lastLoginAt());
    }

    /**
     * Optimistic update using an explicit column map; column names are internal constants.
     *
     * @return number of rows changed (0 means the row no longer exists)
     */
    public int updateFields(String id, Map<String, Object> fields) {
        if (fields.isEmpty()) {
            return 0;
        }
        StringBuilder sql = new StringBuilder("UPDATE platform_users SET ");
        List<Object> args = new ArrayList<>();
        boolean first = true;
        for (Map.Entry<String, Object> entry : fields.entrySet()) {
            if (!COLUMNS.contains(entry.getKey())) {
                throw new IllegalArgumentException("unknown platform_users column: " + entry.getKey());
            }
            if (!first) {
                sql.append(", ");
            }
            sql.append(entry.getKey()).append(" = ?");
            args.add(entry.getValue());
            first = false;
        }
        sql.append(" WHERE id = ?");
        args.add(id);
        return jdbc.update(sql.toString(), args.toArray());
    }

    public int delete(String id) {
        return jdbc.update("DELETE FROM platform_users WHERE id = ?", id);
    }

    public int touchLastLogin(String id, LocalDateTime at) {
        return jdbc.update("UPDATE platform_users SET last_login_at = ?, updated_at = ? WHERE id = ?",
                at, at, id);
    }
}
