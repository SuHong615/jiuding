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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Debug permissions: built-ins are readable by any login, uploaded skills need manager roles. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SkillDebugPermissionTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtService jwtService;

    @Test
    void builtinSkillIsDebuggableByAnyAuthenticatedUser() throws Exception {
        String approver = SkillCenterTestSupport.bearer(SkillCenterTestSupport.approverToken(jwtService));
        mockMvc.perform(post("/api/skills/risk-review/debug")
                        .header("Authorization", approver)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"input\":{\"plan\":\"PRO\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value("1.0.0"));
    }

    @Test
    void uploadedSkillDebugRequiresManagementRole() throws Exception {
        String id = SkillCenterTestSupport.uniqueSkillId();
        String admin = SkillCenterTestSupport.bearer(SkillCenterTestSupport.adminToken(jwtService));
        mockMvc.perform(multipart("/api/skills/upload")
                        .file(SkillCenterTestSupport.zipPart(SkillCenterTestSupport.skillFiles(id, "1.0.0")))
                        .header("Authorization", admin))
                .andExpect(status().isOk());

        String approver = SkillCenterTestSupport.bearer(SkillCenterTestSupport.approverToken(jwtService));
        mockMvc.perform(post("/api/skills/" + id + "/debug")
                        .header("Authorization", approver)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"input\":{\"plan\":\"ENTERPRISE\"}}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40300));

        String tenantAdmin = SkillCenterTestSupport.bearer(SkillCenterTestSupport.tenantAdminToken(jwtService));
        mockMvc.perform(post("/api/skills/" + id + "/debug")
                        .header("Authorization", tenantAdmin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"input\":{\"plan\":\"ENTERPRISE\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.decision").value("NEEDS_REVIEW"));
    }

    @Test
    void debugRequiresAuthentication() throws Exception {
        mockMvc.perform(post("/api/skills/risk-review/debug")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"input\":{}}"))
                .andExpect(status().isUnauthorized());
    }
}
