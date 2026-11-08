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
package com.suhong.jiuding.agent.repository;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.suhong.jiuding.agent.agent.AgentRunView;
import com.suhong.jiuding.agent.agent.Step;
import com.suhong.jiuding.common.api.PageResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

/** {@code agent_runs} persistence (agent_db). */
@Repository
public class AgentRunRepository {

    private static final Logger log = LoggerFactory.getLogger(AgentRunRepository.class);
    private static final TypeReference<List<Step>> STEP_LIST = new TypeReference<>() {
    };

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public AgentRunRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public void insert(AgentRunView view, String tenantId, String inputJson) {
        LocalDateTime createdAt = LocalDateTime.ofInstant(Instant.now(), ZoneOffset.UTC);
        jdbc.update("""
                        INSERT INTO agent_runs
                          (run_id, agent_type, tenant_id, status, model, input_json, output_json,
                           steps_json, tokens_used, duration_ms, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                view.runId(), view.agentType(), tenantId, view.status(), view.model(),
                inputJson, writeJson(view.output()), writeJson(view.steps()),
                view.tokensUsed(), view.durationMs(), createdAt);
    }

    public Optional<AgentRunView> find(String runId) {
        List<AgentRunView> rows = jdbc.query(
                "SELECT * FROM agent_runs WHERE run_id = ?", mapper(), runId);
        return rows.stream().findFirst();
    }

    public PageResult<AgentRunView> page(String agentType, int page, int size) {
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), 100);
        String where = agentType == null || agentType.isBlank() ? "" : " WHERE agent_type = ?";
        long total;
        List<AgentRunView> items;
        if (where.isEmpty()) {
            Long count = jdbc.queryForObject("SELECT COUNT(*) FROM agent_runs", Long.class);
            total = count == null ? 0 : count;
            items = jdbc.query("SELECT * FROM agent_runs ORDER BY created_at DESC LIMIT ? OFFSET ?",
                    mapper(), safeSize, (long) safePage * safeSize);
        } else {
            Long count = jdbc.queryForObject("SELECT COUNT(*) FROM agent_runs" + where, Long.class, agentType);
            total = count == null ? 0 : count;
            items = jdbc.query("SELECT * FROM agent_runs" + where
                            + " ORDER BY created_at DESC LIMIT ? OFFSET ?",
                    mapper(), agentType, safeSize, (long) safePage * safeSize);
        }
        return PageResult.of(items, total, safePage, safeSize);
    }

    public long countAll() {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM agent_runs", Long.class);
        return count == null ? 0 : count;
    }

    public long countToday() {
        LocalDateTime startOfDay = LocalDateTime.ofInstant(
                Instant.now().atZone(ZoneOffset.UTC).toLocalDate().atStartOfDay().toInstant(ZoneOffset.UTC),
                ZoneOffset.UTC);
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM agent_runs WHERE created_at >= ?",
                Long.class, startOfDay);
        return count == null ? 0 : count;
    }

    private RowMapper<AgentRunView> mapper() {
        return (ResultSet rs, int rowNum) -> {
            String createdAt = format(rs.getTimestamp("created_at"));
            return new AgentRunView(
                    rs.getString("run_id"),
                    rs.getString("agent_type"),
                    null,
                    null,
                    rs.getString("status"),
                    readJson(rs.getString("output_json")),
                    null,
                    readSteps(rs.getString("steps_json")),
                    rs.getString("model"),
                    rs.getInt("tokens_used"),
                    rs.getLong("duration_ms"),
                    createdAt,
                    rs.getString("tenant_id"),
                    readJson(rs.getString("input_json")));
        };
    }

    private String writeJson(Object value) {
        try {
            return value == null ? null : objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            log.warn("failed to serialize agent run payload: {}", e.toString());
            return null;
        }
    }

    private Object readJson(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, Object.class);
        } catch (Exception e) {
            return json;
        }
    }

    private List<Step> readSteps(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, STEP_LIST);
        } catch (Exception e) {
            return List.of();
        }
    }

    private static String format(Timestamp timestamp) {
        return timestamp == null ? null
                : timestamp.toInstant().toString();
    }
}
