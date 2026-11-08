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

import com.suhong.jiuding.agent.skill.SkillReview;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/** {@code skill_reviews} audit persistence (agent_db V2). */
@Repository
public class SkillReviewRepository {

    private final JdbcTemplate jdbc;

    public SkillReviewRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public SkillReview insert(String skillId, String skillVersion, String action, String actor, String comment) {
        LocalDateTime now = LocalDateTime.ofInstant(java.time.Instant.now(), ZoneOffset.UTC);
        String id = UUID.randomUUID().toString();
        String safeComment = comment == null || comment.length() <= 512
                ? comment : comment.substring(0, 512);
        jdbc.update("""
                        INSERT INTO skill_reviews (id, skill_id, skill_version, action, actor, comment, at)
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                        """,
                id, skillId, skillVersion, action, actor, safeComment, Timestamp.valueOf(now));
        return new SkillReview(id, skillId, skillVersion, action, actor, safeComment,
                now.toInstant(ZoneOffset.UTC).toString());
    }

    public List<SkillReview> findBySkillId(String skillId) {
        return jdbc.query(
                "SELECT * FROM skill_reviews WHERE skill_id = ? ORDER BY at DESC",
                mapper(), skillId);
    }

    private RowMapper<SkillReview> mapper() {
        return (rs, rowNum) -> {
            Timestamp at = rs.getTimestamp("at");
            return new SkillReview(rs.getString("id"), rs.getString("skill_id"),
                    rs.getString("skill_version"), rs.getString("action"), rs.getString("actor"),
                    rs.getString("comment"),
                    at == null ? null : at.toInstant().toString());
        };
    }
}
