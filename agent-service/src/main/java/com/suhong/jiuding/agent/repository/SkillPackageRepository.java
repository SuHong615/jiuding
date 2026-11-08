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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.suhong.jiuding.agent.skill.SkillPackage;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * {@code skill_packages} persistence (agent_db V2). Plain JdbcTemplate, no JPA.
 *
 * <p>Portable UPDATE-then-INSERT upserts keep H2 and PostgreSQL behaviour identical.
 */
@Repository
public class SkillPackageRepository {

    private static final Logger log = LoggerFactory.getLogger(SkillPackageRepository.class);

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public SkillPackageRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public void insert(SkillPackage pkg) {
        jdbc.update("""
                        INSERT INTO skill_packages
                          (id, version, source, status, manifest_json, instructions, rules_yaml,
                           review_comment, reviewed_by, reviewed_at, created_by, created_at, updated_at, enabled)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                pkg.id(), pkg.version(), pkg.source(), pkg.status(), writeJson(pkg.manifest()),
                pkg.instructions(), pkg.rulesYaml(), pkg.reviewComment(), pkg.reviewedBy(),
                toTimestamp(pkg.reviewedAt()), pkg.createdBy(),
                toTimestamp(pkg.createdAt()), toTimestamp(pkg.updatedAt()), pkg.enabled());
    }

    /** Rename/retarget a DRAFT row to a new version number (same primary key pair). */
    public void renameVersion(String id, String oldVersion, String newVersion, String manifestJson,
                              String instructions, String rulesYaml, LocalDateTime now) {
        jdbc.update("""
                        UPDATE skill_packages
                           SET version = ?, manifest_json = ?, instructions = ?, rules_yaml = ?, updated_at = ?
                         WHERE id = ? AND version = ?
                        """,
                newVersion, manifestJson, instructions, rulesYaml, now, id, oldVersion);
    }

    public void updateContent(String id, String version, String manifestJson, String instructions,
                              String rulesYaml, LocalDateTime now) {
        jdbc.update("""
                        UPDATE skill_packages
                           SET manifest_json = ?, instructions = ?, rules_yaml = ?, updated_at = ?
                         WHERE id = ? AND version = ?
                        """,
                manifestJson, instructions, rulesYaml, now, id, version);
    }

    public void updateStatus(String id, String version, String status, boolean enabled,
                             String reviewComment, String reviewedBy, String reviewedAt,
                             LocalDateTime now) {
        jdbc.update("""
                        UPDATE skill_packages
                           SET status = ?, enabled = ?, review_comment = ?, reviewed_by = ?,
                               reviewed_at = ?, updated_at = ?
                         WHERE id = ? AND version = ?
                        """,
                status, enabled, reviewComment, reviewedBy, toTimestamp(reviewedAt), now, id, version);
    }

    public void resetReview(String id, String version, String status, String manifestJson,
                            String instructions, String rulesYaml, LocalDateTime now) {
        jdbc.update("""
                        UPDATE skill_packages
                           SET status = ?, enabled = FALSE, review_comment = NULL, reviewed_by = NULL,
                               reviewed_at = NULL, manifest_json = ?, instructions = ?, rules_yaml = ?,
                               updated_at = ?
                         WHERE id = ? AND version = ?
                        """,
                status, manifestJson, instructions, rulesYaml, now, id, version);
    }

    public void setEnabled(String id, String version, boolean enabled, LocalDateTime now) {
        jdbc.update("UPDATE skill_packages SET enabled = ?, updated_at = ? WHERE id = ? AND version = ?",
                enabled, now, id, version);
    }

    /** Only one uploaded version per id may be enabled at a time. */
    public void disableOtherUploadedVersions(String id, String keepVersion, LocalDateTime now) {
        jdbc.update("""
                        UPDATE skill_packages SET enabled = FALSE, updated_at = ?
                         WHERE id = ? AND version <> ? AND source = 'UPLOADED' AND enabled = TRUE
                        """,
                now, id, keepVersion);
    }

    public Optional<SkillPackage> find(String id, String version) {
        List<SkillPackage> rows = jdbc.query(
                "SELECT * FROM skill_packages WHERE id = ? AND version = ?", mapper(), id, version);
        return rows.stream().findFirst();
    }

    /** Latest package for an id (any source/status), ordered by updated_at then created_at. */
    public Optional<SkillPackage> findLatest(String id) {
        List<SkillPackage> rows = jdbc.query("""
                SELECT * FROM skill_packages WHERE id = ?
                 ORDER BY updated_at DESC, created_at DESC
                 LIMIT 1
                """, mapper(), id);
        return rows.stream().findFirst();
    }

    /** All rows (latest per id) matching the optional filters. */
    public List<SkillPackage> list(String status, String source, Boolean enabled) {
        StringBuilder sql = new StringBuilder("SELECT * FROM skill_packages WHERE 1 = 1");
        List<Object> args = new ArrayList<>();
        if (status != null) {
            sql.append(" AND status = ?");
            args.add(status);
        }
        if (source != null) {
            sql.append(" AND source = ?");
            args.add(source);
        }
        if (enabled != null) {
            sql.append(" AND enabled = ?");
            args.add(enabled);
        }
        sql.append(" ORDER BY updated_at DESC, created_at DESC");
        List<SkillPackage> rows = jdbc.query(sql.toString(), mapper(), args.toArray());
        Map<String, SkillPackage> latest = new LinkedHashMap<>();
        for (SkillPackage row : rows) {
            SkillPackage existing = latest.get(row.id());
            if (existing == null || compareUpdated(row, existing) > 0) {
                latest.put(row.id(), row);
            }
        }
        return new ArrayList<>(latest.values());
    }

    public List<SkillPackage> listAll() {
        return jdbc.query("SELECT * FROM skill_packages ORDER BY updated_at DESC", mapper());
    }

    /** Every persisted version of one skill id (unsorted; callers order by version). */
    public List<SkillPackage> listVersions(String id) {
        return jdbc.query("SELECT * FROM skill_packages WHERE id = ?", mapper(), id);
    }

    /** Rows the runtime registry must load: uploaded, approved and enabled. */
    public List<SkillPackage> findLoadableUploaded() {
        return jdbc.query("""
                SELECT * FROM skill_packages
                 WHERE source = 'UPLOADED' AND status = 'APPROVED' AND enabled = TRUE
                 ORDER BY updated_at DESC
                """, mapper());
    }

    /** Mirror a built-in skill into the table (UPDATE then INSERT, idempotent). */
    public void upsertBuiltin(SkillPackage pkg) {
        int updated = jdbc.update("""
                        UPDATE skill_packages
                           SET status = 'APPROVED', enabled = TRUE, manifest_json = ?, instructions = ?,
                               rules_yaml = ?, updated_at = ?
                         WHERE id = ? AND version = ? AND source = 'BUILTIN'
                        """,
                writeJson(pkg.manifest()), pkg.instructions(), pkg.rulesYaml(),
                toTimestamp(pkg.updatedAt()), pkg.id(), pkg.version());
        if (updated == 0) {
            jdbc.update("""
                            INSERT INTO skill_packages
                              (id, version, source, status, manifest_json, instructions, rules_yaml,
                               created_by, created_at, updated_at, enabled)
                            VALUES (?, ?, 'BUILTIN', 'APPROVED', ?, ?, ?, 'system', ?, ?, TRUE)
                            """,
                    pkg.id(), pkg.version(), writeJson(pkg.manifest()), pkg.instructions(),
                    pkg.rulesYaml(), toTimestamp(pkg.createdAt()), toTimestamp(pkg.updatedAt()));
        }
    }

    /** Remove stale built-in versions of one id after a version bump on disk. */
    public void deleteBuiltinVersionsExcept(String id, String keepVersion) {
        jdbc.update("DELETE FROM skill_packages WHERE source = 'BUILTIN' AND id = ? AND version <> ?",
                id, keepVersion);
    }

    /** Remove built-in rows whose package disappeared from the skills directory. */
    public void deleteBuiltinNotIn(Set<String> ids) {
        if (ids == null || ids.isEmpty()) {
            jdbc.update("DELETE FROM skill_packages WHERE source = 'BUILTIN'");
            return;
        }
        String placeholders = String.join(",", ids.stream().map(ignored -> "?").toList());
        List<Object> args = new ArrayList<>(ids);
        jdbc.update("DELETE FROM skill_packages WHERE source = 'BUILTIN' AND id NOT IN (" + placeholders + ")",
                args.toArray());
    }

    private RowMapper<SkillPackage> mapper() {
        return (ResultSet rs, int rowNum) -> new SkillPackage(
                rs.getString("id"),
                rs.getString("version"),
                rs.getString("source"),
                rs.getString("status"),
                readJson(rs.getString("manifest_json")),
                rs.getString("instructions"),
                rs.getString("rules_yaml"),
                rs.getString("review_comment"),
                rs.getString("reviewed_by"),
                format(rs.getTimestamp("reviewed_at")),
                rs.getString("created_by"),
                format(rs.getTimestamp("created_at")),
                format(rs.getTimestamp("updated_at")),
                rs.getBoolean("enabled"));
    }

    private static int compareUpdated(SkillPackage left, SkillPackage right) {
        String a = left.updatedAt() == null ? "" : left.updatedAt();
        String b = right.updatedAt() == null ? "" : right.updatedAt();
        return a.compareTo(b);
    }

    private String writeJson(Map<String, Object> manifest) {
        try {
            return objectMapper.writeValueAsString(manifest);
        } catch (Exception e) {
            log.warn("failed to serialize skill manifest: {}", e.toString());
            return "{}";
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readJson(String payload) {
        if (payload == null || payload.isBlank()) {
            return Map.of();
        }
        try {
            Object parsed = objectMapper.readValue(payload, Object.class);
            if (parsed instanceof Map<?, ?> map) {
                Map<String, Object> result = new LinkedHashMap<>();
                map.forEach((key, value) -> result.put(String.valueOf(key), value));
                return result;
            }
        } catch (Exception e) {
            log.warn("failed to parse skill manifest: {}", e.toString());
        }
        return Map.of();
    }

    static LocalDateTime toLocal(String iso) {
        if (iso == null || iso.isBlank()) {
            return null;
        }
        try {
            return LocalDateTime.ofInstant(Instant.parse(iso), ZoneOffset.UTC);
        } catch (Exception e) {
            return null;
        }
    }

    static Timestamp toTimestamp(String iso) {
        LocalDateTime local = toLocal(iso);
        return local == null ? null : Timestamp.valueOf(local);
    }

    static String format(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant().toString();
    }

    public static LocalDateTime nowUtc() {
        return LocalDateTime.ofInstant(Instant.now(), ZoneOffset.UTC);
    }
}
