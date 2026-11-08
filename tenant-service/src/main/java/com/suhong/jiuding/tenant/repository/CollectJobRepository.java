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

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/** Persistence for {@code collect_jobs}; error lists are stored as JSON text. */
@Repository
public class CollectJobRepository {

    private static final String COLUMNS = "id, mode, source_name, status, total, imported, duplicated,"
            + " failed, errors_json, created_at, finished_at";

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final RowMapper<CollectJobRow> rowMapper;

    public CollectJobRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.rowMapper = (rs, rowNum) -> new CollectJobRow(
                rs.getString("id"),
                rs.getString("mode"),
                rs.getString("source_name"),
                rs.getString("status"),
                rs.getInt("total"),
                rs.getInt("imported"),
                rs.getInt("duplicated"),
                rs.getInt("failed"),
                readErrors(rs.getString("errors_json")),
                rs.getObject("created_at", LocalDateTime.class),
                rs.getObject("finished_at", LocalDateTime.class));
    }

    public void insert(CollectJobRow row) {
        jdbc.update("INSERT INTO collect_jobs (" + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                row.id(), row.mode(), row.sourceName(), row.status(), row.total(), row.imported(),
                row.duplicated(), row.failed(), writeErrors(row.errors()), row.createdAt(),
                row.finishedAt());
    }

    public void updateResult(String id, String status, int imported, int duplicated, int failed,
                             List<String> errors, LocalDateTime finishedAt) {
        jdbc.update("UPDATE collect_jobs SET status = ?, imported = ?, duplicated = ?, failed = ?,"
                        + " errors_json = ?, finished_at = ? WHERE id = ?",
                status, imported, duplicated, failed, writeErrors(errors), finishedAt, id);
    }

    public Optional<CollectJobRow> findById(String id) {
        List<CollectJobRow> rows = jdbc.query(
                "SELECT " + COLUMNS + " FROM collect_jobs WHERE id = ?", rowMapper, id);
        return rows.stream().findFirst();
    }

    public List<CollectJobRow> search(int page, int size) {
        return jdbc.query("SELECT " + COLUMNS + " FROM collect_jobs"
                        + " ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?",
                rowMapper, size, (long) page * size);
    }

    public long count() {
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM collect_jobs", Long.class);
        return total == null ? 0L : total;
    }

    private String writeErrors(List<String> errors) {
        if (errors == null || errors.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(errors);
        } catch (Exception ex) {
            throw new IllegalStateException("cannot serialize collection errors", ex);
        }
    }

    private List<String> readErrors(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() { });
        } catch (Exception ex) {
            return List.of("unparseable errors payload");
        }
    }
}
