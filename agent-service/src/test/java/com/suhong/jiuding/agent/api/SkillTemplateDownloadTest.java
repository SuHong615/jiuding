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
import com.suhong.jiuding.agent.skill.SkillLoader;
import com.suhong.jiuding.agent.skill.SkillManifest;
import com.suhong.jiuding.agent.rule.RuleSet;
import com.suhong.jiuding.common.security.JwtService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Template download: a valid, uploadable zip skeleton. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SkillTemplateDownloadTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtService jwtService;

    @Test
    void templateZipContainsParsableSkillAndRules() throws Exception {
        String admin = SkillCenterTestSupport.bearer(SkillCenterTestSupport.adminToken(jwtService));
        byte[] body = mockMvc.perform(get("/api/skills/template").header("Authorization", admin))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition",
                        org.hamcrest.Matchers.containsString("attachment")))
                .andReturn().getResponse().getContentAsByteArray();

        List<String> entries = SkillCenterTestSupport.entries(body);
        assertThat(entries).contains("SKILL.md", "rules.yaml", "README.md");

        String skillMd = readEntry(body, "SKILL.md");
        SkillLoader.ParsedMarkdown parsed = SkillLoader.parseMarkdown(skillMd);
        SkillManifest manifest = SkillLoader.parseManifest(parsed.frontmatter());
        assertThat(manifest.id()).isEqualTo("my-custom-skill");
        assertThat(manifest.type()).isEqualTo("RISK");
        assertThat(parsed.instructions()).isNotBlank();

        RuleSet rules = SkillLoader.parseRuleSet(readEntry(body, "rules.yaml"),
                manifest.id(), manifest.version());
        assertThat(rules.rules()).isNotEmpty();
        assertThat(rules.rules().get(0).id()).isEqualTo("R-CUSTOM-001");
    }

    @Test
    void templateIsServedAsZip() throws Exception {
        String admin = SkillCenterTestSupport.bearer(SkillCenterTestSupport.adminToken(jwtService));
        byte[] body = mockMvc.perform(get("/api/skills/template").header("Authorization", admin))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type",
                        org.hamcrest.Matchers.containsString("zip")))
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(body.length).isGreaterThan(2);
        assertThat(body[0]).isEqualTo((byte) 'P');
        assertThat(body[1]).isEqualTo((byte) 'K');
    }

    private static String readEntry(byte[] zipBytes, String name) throws Exception {
        try (java.util.zip.ZipInputStream zip = new java.util.zip.ZipInputStream(
                new java.io.ByteArrayInputStream(zipBytes), StandardCharsets.UTF_8)) {
            java.util.zip.ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.getName().equals(name)) {
                    return new String(zip.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
        }
        throw new IllegalStateException("zip entry not found: " + name);
    }
}
