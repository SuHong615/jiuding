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
package com.suhong.jiuding.tenant.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.suhong.jiuding.common.security.JwtService;
import com.suhong.jiuding.common.security.Principal;
import com.suhong.jiuding.tenant.permission.RequirePermission;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * docs/20 section 4.5: GET /api/workflows/tenant-lifecycle returns the read-only lifecycle
 * workflow and is readable by every authenticated tenant role through {@code tenant:read}
 * (or {@code org:read} for organization scoped roles).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TenantLifecycleWorkflowApiTest {

    private static final String ENDPOINT = "/api/workflows/tenant-lifecycle";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JwtService jwtService;

    private String platformToken;
    private String tenantAdminToken;
    private String auditorToken;
    private String orgAdminToken;
    private String memberToken;

    @BeforeEach
    void setUp() {
        platformToken = bearer(new Principal("u-admin", "admin", null,
                Set.of("PLATFORM_ADMIN"), "JWT"));
        tenantAdminToken = bearer(new Principal("u-tadmin", "tadmin", "t-acme",
                Set.of("TENANT_ADMIN"), "JWT"));
        auditorToken = bearer(new Principal("u-aud", "auditor", "t-acme",
                Set.of("AUDITOR"), "JWT"));
        orgAdminToken = bearer(new Principal("u-oadmin", "oadmin", "t-acme",
                Set.of("ORG_ADMIN"), "JWT", "org-1"));
        memberToken = bearer(new Principal("u-mem", "member", "t-acme",
                Set.of("MEMBER"), "JWT", "org-1"));
    }

    @AfterEach
    void tearDown() {
        com.suhong.jiuding.common.security.AuthContext.clear();
        com.suhong.jiuding.common.tenant.TenantContext.clear();
    }

    @Test
    void platformAdminReadsTheCompleteWorkflowDefinition() throws Exception {
        MvcResult result = mockMvc.perform(get(ENDPOINT)
                        .header(HttpHeaders.AUTHORIZATION, platformToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.states.length()").value(10))
                .andExpect(jsonPath("$.data.states[0].code").value("DRAFT"))
                .andExpect(jsonPath("$.data.states[0].name").isNotEmpty())
                .andExpect(jsonPath("$.data.states[0].order").value(1))
                .andExpect(jsonPath("$.data.activities.length()").value(10))
                .andExpect(jsonPath("$.data.activities[0].state").value("DRAFT"))
                .andExpect(jsonPath("$.data.activities[0].skillId").value("tenant-intake"))
                .andExpect(jsonPath("$.data.activities[0].skillName").isNotEmpty())
                .andExpect(jsonPath("$.data.activities[0].agentType").value("tenant-intake"))
                .andExpect(jsonPath("$.data.activities[0].outputs").isArray())
                .andExpect(jsonPath("$.data.activities[0].sla").isNotEmpty())
                .andExpect(jsonPath("$.data.transitions").isArray())
                .andReturn();

        JsonNode data = objectMapper.readTree(
                result.getResponse().getContentAsString(StandardCharsets.UTF_8)).path("data");

        Set<String> stateCodes = new java.util.LinkedHashSet<>();
        data.path("states").forEach(state -> stateCodes.add(state.path("code").asText()));
        assertThat(stateCodes).containsExactly("DRAFT", "SUBMITTED", "RISK_REVIEW",
                "PENDING_APPROVAL", "APPROVED", "PROVISIONING", "ACTIVE", "SUSPENDED",
                "REJECTED", "TERMINATED");

        Set<String> skillIds = new java.util.LinkedHashSet<>();
        for (JsonNode activity : data.path("activities")) {
            assertThat(stateCodes).contains(activity.path("state").asText());
            assertThat(activity.path("outputs").size()).isGreaterThan(0);
            skillIds.add(activity.path("skillId").asText());
        }
        assertThat(skillIds).containsExactlyInAnyOrder("tenant-intake", "risk-review",
                "approval-policy", "provisioning", "tenant-rating");

        boolean happyPathEdge = false;
        for (JsonNode transition : data.path("transitions")) {
            assertThat(transition.path("trigger").asText()).isNotBlank();
            if ("DRAFT".equals(transition.path("from").asText())
                    && "SUBMITTED".equals(transition.path("to").asText())) {
                happyPathEdge = true;
            }
        }
        assertThat(happyPathEdge).isTrue();
    }

    @Test
    void everyAuthenticatedTenantRoleCanReadTheWorkflow() throws Exception {
        for (String token : List.of(tenantAdminToken, auditorToken, orgAdminToken, memberToken)) {
            mockMvc.perform(get(ENDPOINT).header(HttpHeaders.AUTHORIZATION, token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(0));
        }
    }

    @Test
    void unauthenticatedCallersAreRejected() throws Exception {
        mockMvc.perform(get(ENDPOINT))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40100));
    }

    @Test
    void controllerDeclaresTheTenantReadPermission() throws Exception {
        RequirePermission annotation = TenantLifecycleWorkflowController.class
                .getMethod("tenantLifecycle")
                .getAnnotation(RequirePermission.class);
        assertThat(annotation).isNotNull();
        assertThat(Arrays.asList(annotation.value())).contains("tenant:read");
    }

    private String bearer(Principal principal) {
        return "Bearer " + jwtService.issue(principal);
    }
}
