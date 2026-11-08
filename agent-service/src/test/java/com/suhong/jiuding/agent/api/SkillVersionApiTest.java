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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Skill version list/detail endpoints (docs/15 §12). */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SkillVersionApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtService jwtService;

    private String admin() {
        return SkillCenterTestSupport.bearer(SkillCenterTestSupport.adminToken(jwtService));
    }

    @Test
    void builtinSkillExposesSingleBuiltinVersion() throws Exception {
        mockMvc.perform(get("/api/skills/risk-review/versions").header("Authorization", admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].version").value("1.0.0"))
                .andExpect(jsonPath("$.data[0].source").value("BUILTIN"))
                .andExpect(jsonPath("$.data[0].status").value("APPROVED"))
                .andExpect(jsonPath("$.data[0].enabled").value(true))
                .andExpect(jsonPath("$.data[0].latest").value(true));
    }

    @Test
    void uploadedSkillVersionsAreListedNewestFirstAndTrackLatest() throws Exception {
        String id = SkillCenterTestSupport.uniqueSkillId();
        upload(id, "1.0.0");
        upload(id, "1.0.9");
        upload(id, "1.0.10");

        mockMvc.perform(get("/api/skills/" + id + "/versions").header("Authorization", admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(3)))
                .andExpect(jsonPath("$.data[0].version").value("1.0.10"))
                .andExpect(jsonPath("$.data[1].version").value("1.0.9"))
                .andExpect(jsonPath("$.data[2].version").value("1.0.0"))
                .andExpect(jsonPath("$.data[0].latest").value(true))
                .andExpect(jsonPath("$.data[0].status").value("DRAFT"))
                .andExpect(jsonPath("$.data[0].source").value("UPLOADED"))
                .andExpect(jsonPath("$.data[1].latest").value(false));

        // approve 1.0.10 -> it becomes the enabled/latest version
        mockMvc.perform(post("/api/skills/" + id + "/submit-review").header("Authorization", admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value("1.0.10"));
        mockMvc.perform(post("/api/skills/" + id + "/review")
                        .header("Authorization", admin())
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"APPROVE\",\"comment\":\"ok\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/skills/" + id + "/versions").header("Authorization", admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[*].version", hasItem("1.0.0")))
                .andExpect(jsonPath("$.data[0].version").value("1.0.10"))
                .andExpect(jsonPath("$.data[0].status").value("APPROVED"))
                .andExpect(jsonPath("$.data[0].enabled").value(true))
                .andExpect(jsonPath("$.data[0].latest").value(true));
    }

    @Test
    void versionDetailReturnsContentForAnyStoredVersion() throws Exception {
        String id = SkillCenterTestSupport.uniqueSkillId();
        upload(id, "1.0.0");
        upload(id, "1.1.0");

        mockMvc.perform(get("/api/skills/" + id + "/versions/1.0.0").header("Authorization", admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(id))
                .andExpect(jsonPath("$.data.version").value("1.0.0"))
                .andExpect(jsonPath("$.data.status").value("DRAFT"))
                .andExpect(jsonPath("$.data.manifest.id").value(id))
                .andExpect(jsonPath("$.data.instructions").isNotEmpty())
                .andExpect(jsonPath("$.data.rulesYaml").isNotEmpty())
                .andExpect(jsonPath("$.data.rules.rules", hasSize(greaterThanOrEqualTo(1))));
    }

    @Test
    void unknownVersionAndUnknownSkillReturn404() throws Exception {
        String id = SkillCenterTestSupport.uniqueSkillId();
        upload(id, "1.0.0");

        mockMvc.perform(get("/api/skills/" + id + "/versions/9.9.9").header("Authorization", admin()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(54400));

        mockMvc.perform(get("/api/skills/no-such-skill-xyz/versions").header("Authorization", admin()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(54400));
    }

    private void upload(String id, String version) throws Exception {
        mockMvc.perform(multipart("/api/skills/upload")
                        .file(SkillCenterTestSupport.zipPart(SkillCenterTestSupport.skillFiles(id, version)))
                        .header("Authorization", admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value(version));
    }
}
