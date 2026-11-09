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
package com.suhong.jiuding.approval.workflowdef;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Explicit JdbcTemplate persistence for {@code workflow_definitions} (V2). */
@Repository
public class WorkflowDefinitionRepository {

    private final JdbcTemplate jdbc;

    public WorkflowDefinitionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(WorkflowDefinitionEntity entity) {
        jdbc.update("""
                        INSERT INTO workflow_definitions (id, code, name, version, status, definition_json,
                                                          created_by, created_at, updated_at, published_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                entity.getId(), entity.getCode(), entity.getName(), entity.getVersion(),
                entity.getStatus().name(), entity.getDefinitionJson(), entity.getCreatedBy(),
                toTimestamp(entity.getCreatedAt()), toTimestamp(entity.getUpdatedAt()),
                toTimestamp(entity.getPublishedAt()));
    }

    public boolean update(WorkflowDefinitionEntity entity) {
        int rows = jdbc.update("""
                        UPDATE workflow_definitions
                           SET code = ?, name = ?, version = ?, status = ?, definition_json = ?,
                               created_by = ?, created_at = ?, updated_at = ?, published_at = ?
                         WHERE id = ?
                        """,
                entity.getCode(), entity.getName(), entity.getVersion(), entity.getStatus().name(),
                entity.getDefinitionJson(), entity.getCreatedBy(), toTimestamp(entity.getCreatedAt()),
                toTimestamp(entity.getUpdatedAt()), toTimestamp(entity.getPublishedAt()), entity.getId());
        return rows == 1;
    }

    public Optional<WorkflowDefinitionEntity> findById(String id) {
        List<WorkflowDefinitionEntity> rows = jdbc.query(
                "SELECT * FROM workflow_definitions WHERE id = ?", mapper, id);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public List<WorkflowDefinitionEntity> list(String code, String status) {
        List<Object> args = new ArrayList<>();
        StringBuilder sql = new StringBuilder("SELECT * FROM workflow_definitions WHERE 1 = 1");
        if (code != null && !code.isBlank()) {
            sql.append(" AND code = ?");
            args.add(code);
        }
        if (status != null && !status.isBlank()) {
            sql.append(" AND status = ?");
            args.add(status);
        }
        sql.append(" ORDER BY code ASC, version DESC");
        return jdbc.query(sql.toString(), mapper, args.toArray());
    }

    public Optional<WorkflowDefinitionEntity> findPublishedByCode(String code) {
        List<WorkflowDefinitionEntity> rows = jdbc.query("""
                        SELECT * FROM workflow_definitions
                         WHERE code = ? AND status = 'PUBLISHED'
                         ORDER BY version DESC LIMIT 1
                        """,
                mapper, code);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public Optional<WorkflowDefinitionEntity> findFirstPublished() {
        List<WorkflowDefinitionEntity> rows = jdbc.query("""
                        SELECT * FROM workflow_definitions
                         WHERE status = 'PUBLISHED'
                         ORDER BY published_at DESC, version DESC LIMIT 1
                        """,
                mapper);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public int maxVersion(String code) {
        Integer max = jdbc.queryForObject(
                "SELECT COALESCE(MAX(version), 0) FROM workflow_definitions WHERE code = ?", Integer.class, code);
        return max == null ? 0 : max;
    }

    /** Archives the previously published version(s) of a code, keeping the version being published. */
    public int archivePublished(String code, String keepId, Instant now) {
        return jdbc.update("""
                        UPDATE workflow_definitions
                           SET status = 'ARCHIVED', updated_at = ?
                         WHERE code = ? AND status = 'PUBLISHED' AND id <> ?
                        """,
                toTimestamp(now), code, keepId);
    }

    public boolean deleteById(String id) {
        return jdbc.update("DELETE FROM workflow_definitions WHERE id = ?", id) == 1;
    }

    private final RowMapper<WorkflowDefinitionEntity> mapper = (rs, rowNum) -> {
        WorkflowDefinitionEntity entity = new WorkflowDefinitionEntity();
        entity.setId(rs.getString("id"));
        entity.setCode(rs.getString("code"));
        entity.setName(rs.getString("name"));
        entity.setVersion(rs.getInt("version"));
        entity.setStatus(WorkflowStatus.parse(rs.getString("status")));
        entity.setDefinitionJson(rs.getString("definition_json"));
        entity.setCreatedBy(rs.getString("created_by"));
        entity.setCreatedAt(toInstant(rs.getTimestamp("created_at")));
        entity.setUpdatedAt(toInstant(rs.getTimestamp("updated_at")));
        entity.setPublishedAt(toInstant(rs.getTimestamp("published_at")));
        return entity;
    };

    private static Timestamp toTimestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private static Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
