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
package com.suhong.jiuding.tenant.web;

import com.suhong.jiuding.common.security.Headers;
import com.suhong.jiuding.tenant.support.TimeUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * docs/15 section 8: {@code POST /internal/maintenance/purge} is protected by the internal
 * token, runs the retention purge and reports the four deleted row counters.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class InternalMaintenancePurgeTest {

    private static final String INTERNAL_TOKEN = "dev-internal-token";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        LocalDateTime now = TimeUtil.now();
        jdbc.update("INSERT INTO tenant_audits (id, tenant_id, at, actor, action, detail)"
                        + " VALUES (?,?,?,?,?,?)",
                UUID.randomUUID().toString(), "fixture-maintenance", now.minusDays(400),
                "tester", "EXPIRED_AUDIT", "maintenance fixture");
        jdbc.update("INSERT INTO account_login_events"
                        + " (id, tenant_id, account_id, username, at, ip, user_agent, success, reason)"
                        + " VALUES (?,?,?,?,?,?,?,?,?)",
                UUID.randomUUID().toString(), "fixture-maintenance", null, "expired-event",
                now.minusDays(100), "127.0.0.1", "maintenance-test", true, null);
        jdbc.update("INSERT INTO collect_jobs"
                        + " (id, mode, source_name, status, total, imported, duplicated, failed, created_at)"
                        + " VALUES (?,?,?,?,?,?,?,?,?)",
                UUID.randomUUID().toString(), "MANUAL_BATCH", "expired-job", "SUCCEEDED",
                0, 0, 0, 0, now.minusDays(100));
    }

    @AfterEach
    void tearDown() {
        com.suhong.jiuding.common.security.AuthContext.clear();
        com.suhong.jiuding.common.tenant.TenantContext.clear();
    }

    @Test
    void purgeEndpointRequiresInternalTokenAndReturnsCounters() throws Exception {
        mockMvc.perform(post("/internal/maintenance/purge"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/internal/maintenance/purge").header(Headers.INTERNAL_TOKEN, "wrong-token"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/internal/maintenance/purge").header(Headers.INTERNAL_TOKEN, INTERNAL_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.loginEvents").value(1))
                .andExpect(jsonPath("$.data.audits").value(1))
                .andExpect(jsonPath("$.data.collectJobs").value(1))
                .andExpect(jsonPath("$.data.tenants").value(0));

        assertThat(expiredRows()).isZero();

        // A second call is idempotent on an already purged database.
        mockMvc.perform(post("/internal/maintenance/purge").header(Headers.INTERNAL_TOKEN, INTERNAL_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.loginEvents").value(0))
                .andExpect(jsonPath("$.data.audits").value(0))
                .andExpect(jsonPath("$.data.collectJobs").value(0));
    }

    private long expiredRows() {
        Long audits = jdbc.queryForObject(
                "SELECT COUNT(*) FROM tenant_audits WHERE action = 'EXPIRED_AUDIT'", Long.class);
        Long events = jdbc.queryForObject(
                "SELECT COUNT(*) FROM account_login_events WHERE username = 'expired-event'", Long.class);
        Long jobs = jdbc.queryForObject(
                "SELECT COUNT(*) FROM collect_jobs WHERE source_name = 'expired-job'", Long.class);
        return (audits == null ? 0 : audits) + (events == null ? 0 : events) + (jobs == null ? 0 : jobs);
    }
}
