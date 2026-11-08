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
package com.suhong.jiuding.approval.repo;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.suhong.jiuding.approval.api.ApprovalOverview;
import com.suhong.jiuding.approval.api.TimelineItem;
import com.suhong.jiuding.approval.domain.ApprovalEntity;
import com.suhong.jiuding.approval.domain.ApprovalNodeEntity;
import com.suhong.jiuding.approval.domain.ApprovalState;
import com.suhong.jiuding.approval.domain.NodeState;
import com.suhong.jiuding.approval.domain.NodeType;
import com.suhong.jiuding.approval.support.Texts;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/** Explicit JdbcTemplate persistence for the approval_db schema (no JPA). */
@Repository
public class ApprovalRepository {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    /** Monotonic clock so timeline rows inserted in the same millisecond keep their order. */
    private final AtomicLong timelineClock = new AtomicLong();

    public ApprovalRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    // ---------------------------------------------------------------- approvals

    public void insert(ApprovalEntity entity) {
        jdbc.update("""
                        INSERT INTO approvals (id, tenant_id, tenant_code, plan, state, current_node, risk_level,
                                               risk_score, auto_decided, decided_by, decision_note, sla_due_at,
                                               escalated, reasons_json, created_at, updated_at, version)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                entity.getId(), entity.getTenantId(), entity.getTenantCode(), entity.getPlan(),
                entity.getState().name(), entity.getCurrentNode(), entity.getRiskLevel(), entity.getRiskScore(),
                entity.isAutoDecided(), entity.getDecidedBy(), entity.getDecisionNote(),
                toTimestamp(entity.getSlaDueAt()), entity.isEscalated(), writeReasons(entity.getReasons()),
                toTimestamp(entity.getCreatedAt()), toTimestamp(entity.getUpdatedAt()), entity.getVersion());
    }

    /** Optimistic-locked update ({@code WHERE id = ? AND version = ?}); bumps the in-memory version. */
    public boolean update(ApprovalEntity entity) {
        long nextVersion = entity.getVersion() + 1;
        int rows = jdbc.update("""
                        UPDATE approvals
                           SET state = ?, current_node = ?, risk_level = ?, risk_score = ?, auto_decided = ?,
                               decided_by = ?, decision_note = ?, sla_due_at = ?, escalated = ?,
                               updated_at = ?, version = ?
                         WHERE id = ? AND version = ?
                        """,
                entity.getState().name(), entity.getCurrentNode(), entity.getRiskLevel(), entity.getRiskScore(),
                entity.isAutoDecided(), entity.getDecidedBy(), entity.getDecisionNote(),
                toTimestamp(entity.getSlaDueAt()), entity.isEscalated(), toTimestamp(entity.getUpdatedAt()),
                nextVersion, entity.getId(), entity.getVersion());
        if (rows == 1) {
            entity.setVersion(nextVersion);
            return true;
        }
        return false;
    }

    public Optional<ApprovalEntity> findById(String id) {
        List<ApprovalEntity> rows = jdbc.query("SELECT * FROM approvals WHERE id = ?", approvalMapper, id);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public List<ApprovalEntity> findPage(String state, String tenantId, int page, int size) {
        List<Object> args = new ArrayList<>();
        String where = buildWhere(state, tenantId, args);
        args.add(size);
        args.add((long) page * size);
        return jdbc.query("SELECT * FROM approvals" + where + " ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?",
                approvalMapper, args.toArray());
    }

    public long count(String state, String tenantId) {
        List<Object> args = new ArrayList<>();
        String where = buildWhere(state, tenantId, args);
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM approvals" + where, Long.class, args.toArray());
        return total == null ? 0 : total;
    }

    public List<ApprovalEntity> findPending(String tenantId, int limit) {
        List<Object> args = new ArrayList<>();
        String sql = "SELECT * FROM approvals WHERE state = 'PENDING_APPROVAL'";
        if (tenantId != null) {
            sql += " AND tenant_id = ?";
            args.add(tenantId);
        }
        sql += " ORDER BY sla_due_at ASC, created_at ASC LIMIT ?";
        args.add(limit);
        return jdbc.query(sql, approvalMapper, args.toArray());
    }

    public Optional<ApprovalEntity> findActiveByTenant(String tenantId) {
        List<ApprovalEntity> rows = jdbc.query("""
                        SELECT * FROM approvals
                         WHERE tenant_id = ?
                           AND state IN ('RISK_CHECK', 'AUTO_DECISION', 'AUTO_APPROVED', 'PENDING_APPROVAL',
                                         'APPROVED', 'PROVISIONING')
                         ORDER BY created_at DESC LIMIT 1
                        """,
                approvalMapper, tenantId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public List<String> findOverduePendingIds(Instant now, int limit) {
        return jdbc.query("""
                        SELECT id FROM approvals
                         WHERE state = 'PENDING_APPROVAL' AND escalated = FALSE
                           AND sla_due_at IS NOT NULL AND sla_due_at < ?
                         ORDER BY sla_due_at ASC LIMIT ?
                        """,
                (rs, rowNum) -> rs.getString("id"), toTimestamp(now), limit);
    }

    public List<String> findProvisioningIds(int limit) {
        return jdbc.query("SELECT id FROM approvals WHERE state = 'PROVISIONING' ORDER BY updated_at ASC LIMIT ?",
                (rs, rowNum) -> rs.getString("id"), limit);
    }

    /** Atomically marks an overdue approval as escalated; returns 1 only once. */
    public int markEscalated(String id, Instant now) {
        return jdbc.update("""
                        UPDATE approvals
                           SET escalated = TRUE, updated_at = ?, version = version + 1
                         WHERE id = ? AND state = 'PENDING_APPROVAL' AND escalated = FALSE
                        """,
                toTimestamp(now), id);
    }

    public ApprovalOverview overview(String tenantId, Instant now) {
        long pending = countWhere("state = 'PENDING_APPROVAL'", tenantId);
        long approved = countWhere("state IN ('APPROVED', 'PROVISIONING', 'ACTIVE')", tenantId);
        long rejected = countWhere("state = 'REJECTED'", tenantId);
        long autoApproved = countWhere(
                "auto_decided = TRUE AND state IN ('APPROVED', 'PROVISIONING', 'ACTIVE')", tenantId);
        long slaBreached = countWhere(
                "state = 'PENDING_APPROVAL' AND sla_due_at IS NOT NULL AND sla_due_at < ?", tenantId, toTimestamp(now));
        return new ApprovalOverview(pending, autoApproved, approved, rejected, slaBreached);
    }

    // -------------------------------------------------------------------- nodes

    public ApprovalNodeEntity insertNode(ApprovalNodeEntity entity) {
        if (entity.getId() == null) {
            entity.setId(UUID.randomUUID().toString());
        }
        jdbc.update("""
                        INSERT INTO approval_nodes (id, approval_id, seq, node, node_type, state, assignee_role,
                                                    decided_by, decided_at, comment, sla_hours)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                entity.getId(), entity.getApprovalId(), entity.getSeq(), entity.getNode(),
                entity.getNodeType().name(), entity.getState().name(), entity.getAssigneeRole(),
                entity.getDecidedBy(), toTimestamp(entity.getDecidedAt()), entity.getComment(), entity.getSlaHours());
        return entity;
    }

    public int nextNodeSeq(String approvalId) {
        Integer max = jdbc.queryForObject(
                "SELECT COALESCE(MAX(seq), 0) FROM approval_nodes WHERE approval_id = ?", Integer.class, approvalId);
        return (max == null ? 0 : max) + 1;
    }

    public List<ApprovalNodeEntity> findNodes(String approvalId) {
        return jdbc.query("SELECT * FROM approval_nodes WHERE approval_id = ? ORDER BY seq ASC", nodeMapper, approvalId);
    }

    public Optional<ApprovalNodeEntity> findPendingHumanNode(String approvalId) {
        List<ApprovalNodeEntity> rows = jdbc.query("""
                        SELECT * FROM approval_nodes
                         WHERE approval_id = ? AND node_type = 'HUMAN' AND state = 'PENDING'
                         ORDER BY seq ASC LIMIT 1
                        """,
                nodeMapper, approvalId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public boolean updateNode(ApprovalNodeEntity entity) {
        int rows = jdbc.update("""
                        UPDATE approval_nodes
                           SET state = ?, assignee_role = ?, decided_by = ?, decided_at = ?, comment = ?, sla_hours = ?
                         WHERE id = ?
                        """,
                entity.getState().name(), entity.getAssigneeRole(), entity.getDecidedBy(),
                toTimestamp(entity.getDecidedAt()), entity.getComment(), entity.getSlaHours(), entity.getId());
        return rows == 1;
    }

    // ----------------------------------------------------------------- timeline

    public void appendTimeline(String approvalId, String actor, String action, String detail) {
        long now = System.currentTimeMillis();
        long at = timelineClock.updateAndGet(previous -> Math.max(now, previous + 1));
        jdbc.update("""
                        INSERT INTO approval_timeline (id, approval_id, at, actor, action, detail)
                        VALUES (?, ?, ?, ?, ?, ?)
                        """,
                UUID.randomUUID().toString(), approvalId, Timestamp.from(Instant.ofEpochMilli(at)),
                Texts.truncate(actor, 64), Texts.truncate(action, 64), Texts.truncate(detail, 1024));
    }

    public List<TimelineItem> findTimeline(String approvalId) {
        return jdbc.query("SELECT * FROM approval_timeline WHERE approval_id = ? ORDER BY at ASC",
                (rs, rowNum) -> new TimelineItem(
                        toInstant(rs.getTimestamp("at")).toString(),
                        rs.getString("actor"),
                        rs.getString("action"),
                        rs.getString("detail")),
                approvalId);
    }

    // -------------------------------------------------------------------- helpers

    private final RowMapper<ApprovalEntity> approvalMapper = (rs, rowNum) -> {
        ApprovalEntity entity = new ApprovalEntity();
        entity.setId(rs.getString("id"));
        entity.setTenantId(rs.getString("tenant_id"));
        entity.setTenantCode(rs.getString("tenant_code"));
        entity.setPlan(rs.getString("plan"));
        entity.setState(ApprovalState.valueOf(rs.getString("state")));
        entity.setCurrentNode(rs.getString("current_node"));
        entity.setRiskLevel(rs.getString("risk_level"));
        int score = rs.getInt("risk_score");
        entity.setRiskScore(rs.wasNull() ? null : score);
        entity.setAutoDecided(rs.getBoolean("auto_decided"));
        entity.setDecidedBy(rs.getString("decided_by"));
        entity.setDecisionNote(rs.getString("decision_note"));
        entity.setSlaDueAt(toInstant(rs.getTimestamp("sla_due_at")));
        entity.setEscalated(rs.getBoolean("escalated"));
        entity.setReasons(readReasons(rs.getString("reasons_json")));
        entity.setCreatedAt(toInstant(rs.getTimestamp("created_at")));
        entity.setUpdatedAt(toInstant(rs.getTimestamp("updated_at")));
        entity.setVersion(rs.getLong("version"));
        return entity;
    };

    private final RowMapper<ApprovalNodeEntity> nodeMapper = (rs, rowNum) -> {
        ApprovalNodeEntity entity = new ApprovalNodeEntity();
        entity.setId(rs.getString("id"));
        entity.setApprovalId(rs.getString("approval_id"));
        entity.setSeq(rs.getInt("seq"));
        entity.setNode(rs.getString("node"));
        entity.setNodeType(NodeType.valueOf(rs.getString("node_type")));
        entity.setState(NodeState.valueOf(rs.getString("state")));
        entity.setAssigneeRole(rs.getString("assignee_role"));
        entity.setDecidedBy(rs.getString("decided_by"));
        entity.setDecidedAt(toInstant(rs.getTimestamp("decided_at")));
        entity.setComment(rs.getString("comment"));
        int slaHours = rs.getInt("sla_hours");
        entity.setSlaHours(rs.wasNull() ? null : slaHours);
        return entity;
    };

    private String buildWhere(String state, String tenantId, List<Object> args) {
        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        if (state != null && !state.isBlank()) {
            where.append(" AND state = ?");
            args.add(state);
        }
        if (tenantId != null && !tenantId.isBlank()) {
            where.append(" AND tenant_id = ?");
            args.add(tenantId);
        }
        return where.toString();
    }

    private long countWhere(String predicate, String tenantId, Object... predicateArgs) {
        List<Object> args = new ArrayList<>(List.of(predicateArgs));
        String where = " WHERE " + predicate;
        if (tenantId != null && !tenantId.isBlank()) {
            where += " AND tenant_id = ?";
            args.add(tenantId);
        }
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM approvals" + where, Long.class, args.toArray());
        return total == null ? 0 : total;
    }

    private String writeReasons(List<String> reasons) {
        try {
            return objectMapper.writeValueAsString(reasons == null ? Collections.emptyList() : reasons);
        } catch (Exception ex) {
            return "[]";
        }
    }

    private List<String> readReasons(String json) {
        if (json == null || json.isBlank()) {
            return Collections.emptyList();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {
            });
        } catch (Exception ex) {
            return Collections.emptyList();
        }
    }

    private static Timestamp toTimestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private static Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
