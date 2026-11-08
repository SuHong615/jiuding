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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Skill package upload validation: zip structure, size, rules syntax, conflicts. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SkillUploadValidationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtService jwtService;

    private String admin() {
        return SkillCenterTestSupport.bearer(SkillCenterTestSupport.adminToken(jwtService));
    }

    @Test
    void validUploadCreatesDraftPackage() throws Exception {
        String id = SkillCenterTestSupport.uniqueSkillId();
        MockMultipartFile file = SkillCenterTestSupport.zipPart(
                SkillCenterTestSupport.skillFiles(id, "1.0.0"));

        mockMvc.perform(multipart("/api/skills/upload")
                        .file(file)
                        .param("submitForReview", "false")
                        .header("Authorization", admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(id))
                .andExpect(jsonPath("$.data.source").value("UPLOADED"))
                .andExpect(jsonPath("$.data.status").value("DRAFT"))
                .andExpect(jsonPath("$.data.enabled").value(false))
                .andExpect(jsonPath("$.data.createdBy").value("admin"));

        mockMvc.perform(get("/api/skills/" + id).header("Authorization", admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DRAFT"))
                .andExpect(jsonPath("$.data.instructions").isNotEmpty())
                .andExpect(jsonPath("$.data.rulesYaml").isNotEmpty());
    }

    @Test
    void submitForReviewOnUploadGoesStraightToPendingReview() throws Exception {
        String id = SkillCenterTestSupport.uniqueSkillId();
        mockMvc.perform(multipart("/api/skills/upload")
                        .file(SkillCenterTestSupport.zipPart(SkillCenterTestSupport.skillFiles(id, "1.0.0")))
                        .param("submitForReview", "true")
                        .header("Authorization", admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PENDING_REVIEW"));
    }

    @Test
    void oversizedPackageIsRejected() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "big.zip", "application/zip",
                new byte[1024 * 1024 + 1]);
        mockMvc.perform(multipart("/api/skills/upload")
                        .file(file)
                        .header("Authorization", admin()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40001))
                .andExpect(jsonPath("$.message", containsString("1MB")));
    }

    @Test
    void packageWithoutSkillMdIsRejected() throws Exception {
        Map<String, String> files = Map.of("README.md", "no skill here");
        mockMvc.perform(multipart("/api/skills/upload")
                        .file(SkillCenterTestSupport.zipPart(files))
                        .header("Authorization", admin()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40001))
                .andExpect(jsonPath("$.message", containsString("SKILL.md")));
    }

    @Test
    void invalidRulesSyntaxIsRejected() throws Exception {
        String id = SkillCenterTestSupport.uniqueSkillId();
        Map<String, String> files = SkillCenterTestSupport.skillFiles(id, "1.0.0");
        files.put("rules.yaml", """
                skillId: %s
                rules:
                  - id: R-BAD
                    when: { field: plan, op: between, value: 1 }
                    then: [ { action: addScore, value: 1 } ]
                """.formatted(id));
        mockMvc.perform(multipart("/api/skills/upload")
                        .file(SkillCenterTestSupport.zipPart(files))
                        .header("Authorization", admin()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40001));
    }

    @Test
    void builtinIdConflictIsRejectedWith409() throws Exception {
        Map<String, String> files = SkillCenterTestSupport.skillFiles("risk-review", "9.9.9");
        mockMvc.perform(multipart("/api/skills/upload")
                        .file(SkillCenterTestSupport.zipPart(files))
                        .header("Authorization", admin()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(40900))
                .andExpect(jsonPath("$.message", containsString("内置")));
    }

    @Test
    void duplicateVersionIsRejectedWith409() throws Exception {
        String id = SkillCenterTestSupport.uniqueSkillId();
        byte[] zip = SkillCenterTestSupport.zip(SkillCenterTestSupport.skillFiles(id, "1.0.0"));
        MockMultipartFile file = new MockMultipartFile("file", "skill.zip", "application/zip", zip);

        mockMvc.perform(multipart("/api/skills/upload").file(file)
                        .header("Authorization", admin()))
                .andExpect(status().isOk());
        mockMvc.perform(multipart("/api/skills/upload")
                        .file(new MockMultipartFile("file", "skill.zip", "application/zip", zip))
                        .header("Authorization", admin()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message", containsString("版本已存在")));
    }

    @Test
    void uploadRequiresManagementRole() throws Exception {
        String id = SkillCenterTestSupport.uniqueSkillId();
        String approver = SkillCenterTestSupport.bearer(SkillCenterTestSupport.approverToken(jwtService));
        mockMvc.perform(multipart("/api/skills/upload")
                        .file(SkillCenterTestSupport.zipPart(SkillCenterTestSupport.skillFiles(id, "1.0.0")))
                        .header("Authorization", approver))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40300));
    }

    @Test
    void uploadedPackageAppearsInSourceFilteredList() throws Exception {
        String id = SkillCenterTestSupport.uniqueSkillId();
        mockMvc.perform(multipart("/api/skills/upload")
                        .file(SkillCenterTestSupport.zipPart(SkillCenterTestSupport.skillFiles(id, "1.0.0")))
                        .header("Authorization", admin()))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/skills").param("source", "UPLOADED").param("status", "DRAFT")
                        .header("Authorization", admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[*].id", hasItem(id)));
    }
}
