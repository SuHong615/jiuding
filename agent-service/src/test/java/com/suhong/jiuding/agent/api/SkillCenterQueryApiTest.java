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
package com.suhong.jiuding.agent.api;

import com.suhong.jiuding.agent.support.SkillCenterTestSupport;
import com.suhong.jiuding.common.security.JwtService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Skill center query surface: filtered list, enriched detail, review history. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SkillCenterQueryApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtService jwtService;

    private String admin() {
        return SkillCenterTestSupport.bearer(SkillCenterTestSupport.adminToken(jwtService));
    }

    @Test
    void builtinListFiltersWork() throws Exception {
        mockMvc.perform(get("/api/skills").param("source", "BUILTIN")
                        .header("Authorization", admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(5)))
                .andExpect(jsonPath("$.data[*].source", hasItem("BUILTIN")))
                .andExpect(jsonPath("$.data[*].status", hasItem("APPROVED")))
                .andExpect(jsonPath("$.data[*].enabled", hasItem(true)));

        mockMvc.perform(get("/api/skills").param("enabled", "false")
                        .header("Authorization", admin()))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/skills").param("status", "NOT_A_STATUS")
                        .header("Authorization", admin()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40001));
    }

    @Test
    void detailExposesManifestRulesYamlAndReviewFields() throws Exception {
        mockMvc.perform(get("/api/skills/risk-review").header("Authorization", admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value("risk-review"))
                .andExpect(jsonPath("$.data.source").value("BUILTIN"))
                .andExpect(jsonPath("$.data.status").value("APPROVED"))
                .andExpect(jsonPath("$.data.enabled").value(true))
                .andExpect(jsonPath("$.data.manifest.id").value("risk-review"))
                .andExpect(jsonPath("$.data.manifest.rules", hasSize(greaterThanOrEqualTo(6))))
                .andExpect(jsonPath("$.data.rules.rules", hasSize(greaterThanOrEqualTo(6))))
                .andExpect(jsonPath("$.data.rulesYaml").isNotEmpty())
                .andExpect(jsonPath("$.data.instructions").isNotEmpty());
    }

    @Test
    void uploadedPackageDetailCarriesReviewState() throws Exception {
        String id = SkillCenterTestSupport.uniqueSkillId();
        mockMvc.perform(multipart("/api/skills/upload")
                        .file(SkillCenterTestSupport.zipPart(SkillCenterTestSupport.skillFiles(id, "1.0.0")))
                        .param("submitForReview", "true")
                        .header("Authorization", admin()))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/skills").param("source", "UPLOADED")
                        .param("status", "PENDING_REVIEW")
                        .header("Authorization", admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[*].id", hasItem(id)));

        mockMvc.perform(get("/api/skills/" + id).header("Authorization", admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.source").value("UPLOADED"))
                .andExpect(jsonPath("$.data.status").value("PENDING_REVIEW"))
                .andExpect(jsonPath("$.data.reviewComment").doesNotExist())
                .andExpect(jsonPath("$.data.rulesYaml").isNotEmpty());
    }

    @Test
    void reviewHistoryTracksTheLifecycle() throws Exception {
        String id = SkillCenterTestSupport.uniqueSkillId();
        mockMvc.perform(multipart("/api/skills/upload")
                        .file(SkillCenterTestSupport.zipPart(SkillCenterTestSupport.skillFiles(id, "1.0.0")))
                        .param("submitForReview", "true")
                        .header("Authorization", admin()))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/skills/" + id + "/review")
                        .header("Authorization", admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"REJECT\",\"comment\":\"补规则\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/skills/" + id + "/submit-review").header("Authorization", admin()))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/skills/" + id + "/reviews").header("Authorization", admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(greaterThanOrEqualTo(4))))
                .andExpect(jsonPath("$.data[*].action", hasItem("UPLOAD")))
                .andExpect(jsonPath("$.data[*].action", hasItem("SUBMIT")))
                .andExpect(jsonPath("$.data[*].action", hasItem("REJECT")))
                .andExpect(jsonPath("$.data[*].actor", hasItem("admin")))
                .andExpect(jsonPath("$.data[0].skillId").value(id))
                .andExpect(jsonPath("$.data[0].at").isNotEmpty());
    }

    @Test
    void unknownSkillReturnsSkillNotFoundCode() throws Exception {
        mockMvc.perform(get("/api/skills/does-not-exist-xyz").header("Authorization", admin()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(54400));
    }

    @Test
    void listRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/skills")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/skills/template")).andExpect(status().isUnauthorized());
    }
}
