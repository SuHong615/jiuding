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

import com.suhong.jiuding.agent.skill.TraceItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** {@code rule_hits} audit persistence (agent_db). */
@Repository
public class RuleHitRepository {

    private static final Logger log = LoggerFactory.getLogger(RuleHitRepository.class);

    private final JdbcTemplate jdbc;

    public RuleHitRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Persist one row per evaluated rule. Audit failures never break the caller
     * (the rule outcome is already returned to the client).
     */
    public void saveAll(String runId, String skillId, List<TraceItem> trace) {
        if (trace == null || trace.isEmpty()) {
            return;
        }
        try {
            List<Object[]> batch = new ArrayList<>(trace.size());
            for (TraceItem item : trace) {
                String message = item.reason();
                if (message != null && message.length() > 512) {
                    message = message.substring(0, 512);
                }
                batch.add(new Object[]{
                        UUID.randomUUID().toString(), runId, skillId, item.ruleId(),
                        item.severity(), message, item.matched()
                });
            }
            jdbc.batchUpdate("""
                    INSERT INTO rule_hits (id, run_id, skill_id, rule_id, severity, message, matched)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """, batch);
        } catch (Exception e) {
            log.warn("failed to persist rule hits for run {}: {}", runId, e.toString());
        }
    }
}
