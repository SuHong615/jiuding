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
package com.suhong.jiuding.tenant.onboarding;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/** Pure JdbcTemplate persistence for {@code onboarding_applications}. */
@Repository
public class OnboardingApplicationRepository {

    private static final String COLUMNS = "id, application_no, company_name, tenant_code, contact_name,"
            + " contact_email, contact_phone, plan, region, industry, notes, status, tenant_id,"
            + " agent_decision, agent_risk_level, agent_score, agent_reasons, reviewed_by,"
            + " reviewed_at, created_at, updated_at";

    private static final RowMapper<OnboardingApplicationRow> ROW_MAPPER = (rs, rowNum) ->
            new OnboardingApplicationRow(
                    rs.getString("id"),
                    rs.getString("application_no"),
                    rs.getString("company_name"),
                    rs.getString("tenant_code"),
                    rs.getString("contact_name"),
                    rs.getString("contact_email"),
                    rs.getString("contact_phone"),
                    rs.getString("plan"),
                    rs.getString("region"),
                    rs.getString("industry"),
                    rs.getString("notes"),
                    rs.getString("status"),
                    rs.getString("tenant_id"),
                    rs.getString("agent_decision"),
                    rs.getString("agent_risk_level"),
                    (Integer) rs.getObject("agent_score"),
                    rs.getString("agent_reasons"),
                    rs.getString("reviewed_by"),
                    rs.getObject("reviewed_at", LocalDateTime.class),
                    rs.getObject("created_at", LocalDateTime.class),
                    rs.getObject("updated_at", LocalDateTime.class));

    private final JdbcTemplate jdbc;

    public OnboardingApplicationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(OnboardingApplicationRow row) {
        jdbc.update("INSERT INTO onboarding_applications (" + COLUMNS + ")"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                row.id(), row.applicationNo(), row.companyName(), row.tenantCode(),
                row.contactName(), row.contactEmail(), row.contactPhone(), row.plan(),
                row.region(), row.industry(), row.notes(), row.status(), row.tenantId(),
                row.agentDecision(), row.agentRiskLevel(), row.agentScore(), row.agentReasons(),
                row.reviewedBy(), row.reviewedAt(), row.createdAt(), row.updatedAt());
    }

    public void update(OnboardingApplicationRow row) {
        jdbc.update("UPDATE onboarding_applications SET status = ?, agent_decision = ?,"
                        + " agent_risk_level = ?, agent_score = ?, agent_reasons = ?, reviewed_by = ?,"
                        + " reviewed_at = ?, updated_at = ? WHERE id = ?",
                row.status(), row.agentDecision(), row.agentRiskLevel(), row.agentScore(),
                row.agentReasons(), row.reviewedBy(), row.reviewedAt(), row.updatedAt(), row.id());
    }

    public Optional<OnboardingApplicationRow> findByApplicationNo(String applicationNo) {
        List<OnboardingApplicationRow> rows = jdbc.query(
                "SELECT " + COLUMNS + " FROM onboarding_applications WHERE application_no = ?",
                ROW_MAPPER, applicationNo);
        return rows.stream().findFirst();
    }

    public Optional<OnboardingApplicationRow> findByTenantId(String tenantId) {
        List<OnboardingApplicationRow> rows = jdbc.query(
                "SELECT " + COLUMNS + " FROM onboarding_applications WHERE tenant_id = ?",
                ROW_MAPPER, tenantId);
        return rows.stream().findFirst();
    }
}
