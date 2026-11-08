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

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Map;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Lifecycle: upload → submit → review (approve/reject) → enable/disable, plus built-in read-only rules. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SkillReviewFlowTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private ObjectMapper objectMapper;

    private String admin() {
        return SkillCenterTestSupport.bearer(SkillCenterTestSupport.adminToken(jwtService));
    }

    private String approver() {
        return SkillCenterTestSupport.bearer(SkillCenterTestSupport.approverToken(jwtService));
    }

    private void upload(String id, boolean submitForReview) throws Exception {
        mockMvc.perform(multipart("/api/skills/upload")
                        .file(SkillCenterTestSupport.zipPart(SkillCenterTestSupport.skillFiles(id, "1.0.0")))
                        .param("submitForReview", String.valueOf(submitForReview))
                        .header("Authorization", admin()))
                .andExpect(status().isOk());
    }

    @Test
    void approveFlowEnablesHotLoadsAndRunsUploadedSkill() throws Exception {
        String id = SkillCenterTestSupport.uniqueSkillId();
        upload(id, true);

        // only platform admins may review
        mockMvc.perform(post("/api/skills/" + id + "/review")
                        .header("Authorization", approver())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"APPROVE\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40300));

        // rejection requires a comment
        mockMvc.perform(post("/api/skills/" + id + "/review")
                        .header("Authorization", admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"REJECT\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40001));

        mockMvc.perform(post("/api/skills/" + id + "/review")
                        .header("Authorization", admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"REJECT\",\"comment\":\"规则不完整\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("REJECTED"))
                .andExpect(jsonPath("$.data.enabled").value(false))
                .andExpect(jsonPath("$.data.reviewComment").value("规则不完整"));

        // resubmit and approve
        mockMvc.perform(post("/api/skills/" + id + "/submit-review").header("Authorization", admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PENDING_REVIEW"));

        mockMvc.perform(post("/api/skills/" + id + "/review")
                        .header("Authorization", admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"APPROVE\",\"comment\":\"通过\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("APPROVED"))
                .andExpect(jsonPath("$.data.enabled").value(true))
                .andExpect(jsonPath("$.data.reviewedBy").value("admin"));

        // hot-loaded: the uploaded skill executes through the same rule engine
        mockMvc.perform(post("/api/skills/" + id + "/run")
                        .header("X-API-Key", "dev-mcp-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"input\":{\"plan\":\"ENTERPRISE\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.skillId").value(id))
                .andExpect(jsonPath("$.data.score").value(50))
                .andExpect(jsonPath("$.data.decision").value("NEEDS_REVIEW"));

        // disable unloads it, enable brings it back
        mockMvc.perform(post("/api/skills/" + id + "/disable").header("Authorization", admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.enabled").value(false));
        mockMvc.perform(post("/api/skills/" + id + "/run")
                        .header("X-API-Key", "dev-mcp-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(54400));

        mockMvc.perform(post("/api/skills/" + id + "/enable").header("Authorization", admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.enabled").value(true));
        mockMvc.perform(post("/api/skills/" + id + "/run")
                        .header("X-API-Key", "dev-mcp-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"input\":{\"plan\":\"PRO\"}}"))
                .andExpect(status().isOk());
    }

    @Test
    void builtinSkillsAreReadOnly() throws Exception {
        mockMvc.perform(put("/api/skills/risk-review")
                        .header("Authorization", admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "version", "1.0.1", "instructions", "tampered"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message", containsString("内置技能只读")));

        mockMvc.perform(post("/api/skills/risk-review/disable").header("Authorization", admin()))
                .andExpect(status().isConflict());
        mockMvc.perform(post("/api/skills/risk-review/submit-review").header("Authorization", admin()))
                .andExpect(status().isConflict());
        mockMvc.perform(delete("/api/skills/risk-review").header("Authorization", admin()))
                .andExpect(status().isConflict());
    }

    @Test
    void tenantAdminCanEditDraftButCannotReview() throws Exception {
        String id = SkillCenterTestSupport.uniqueSkillId();
        String tenantAdmin = SkillCenterTestSupport.bearer(SkillCenterTestSupport.tenantAdminToken(jwtService));
        mockMvc.perform(multipart("/api/skills/upload")
                        .file(SkillCenterTestSupport.zipPart(SkillCenterTestSupport.skillFiles(id, "1.0.0")))
                        .header("Authorization", tenantAdmin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DRAFT"));

        mockMvc.perform(post("/api/skills/" + id + "/submit-review").header("Authorization", tenantAdmin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PENDING_REVIEW"));

        mockMvc.perform(post("/api/skills/" + id + "/review")
                        .header("Authorization", tenantAdmin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"APPROVE\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void deleteMovesUploadedPackageToDisabled() throws Exception {
        String id = SkillCenterTestSupport.uniqueSkillId();
        upload(id, false);
        mockMvc.perform(delete("/api/skills/" + id).header("Authorization", admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DISABLED"))
                .andExpect(jsonPath("$.data.enabled").value(false));
        mockMvc.perform(get("/api/skills/" + id).header("Authorization", admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DISABLED"));
    }

    @Test
    void updateRejectedPackageCreatesNewDraftVersion() throws Exception {
        String id = SkillCenterTestSupport.uniqueSkillId();
        upload(id, true);
        mockMvc.perform(post("/api/skills/" + id + "/review")
                        .header("Authorization", admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"REJECT\",\"comment\":\"补充规则\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(put("/api/skills/" + id)
                        .header("Authorization", admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "version", "1.0.1",
                                "description", "更新后的描述",
                                "instructions", "# 角色\n更新后的指令"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DRAFT"))
                .andExpect(jsonPath("$.data.version").value("1.0.1"));

        mockMvc.perform(get("/api/skills/" + id).header("Authorization", admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value("1.0.1"))
                .andExpect(jsonPath("$.data.status").value("DRAFT"))
                .andExpect(jsonPath("$.data.description").value("更新后的描述"));
    }
}
