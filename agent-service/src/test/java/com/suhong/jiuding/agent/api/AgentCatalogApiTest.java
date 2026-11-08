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

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Agent capability catalog (docs/15 §3). */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AgentCatalogApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void catalogListsTheFourAgentsReadableMetadata() throws Exception {
        mockMvc.perform(get("/api/agents/catalog").header("X-API-Key", "dev-mcp-key"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data", hasSize(4)))
                .andExpect(jsonPath("$.data[*].agentType", hasItem("tenant-intake")))
                .andExpect(jsonPath("$.data[*].agentType", hasItem("risk-review")))
                .andExpect(jsonPath("$.data[*].agentType", hasItem("approval")))
                .andExpect(jsonPath("$.data[*].agentType", hasItem("provisioning")))
                .andExpect(jsonPath("$.data[*].displayName", everyItem(not(org.hamcrest.Matchers.emptyString()))))
                .andExpect(jsonPath("$.data[*].description", everyItem(not(org.hamcrest.Matchers.emptyString()))))
                .andExpect(jsonPath("$.data[*].inputs", everyItem(notNullValue())))
                .andExpect(jsonPath("$.data[*].outputs", everyItem(notNullValue())));
    }

    @Test
    void catalogEntriesCarryExampleInput() throws Exception {
        mockMvc.perform(get("/api/agents/catalog").header("X-API-Key", "dev-mcp-key"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.agentType == 'risk-review')].displayName",
                        hasItem("租户风险审查")))
                .andExpect(jsonPath("$.data[?(@.agentType == 'risk-review')].exampleInput.attributes.industry",
                        hasItem("gambling")))
                .andExpect(jsonPath("$.data[?(@.agentType == 'provisioning')].displayName",
                        hasItem("资源开通")))
                .andExpect(jsonPath("$.data[?(@.agentType == 'provisioning')].exampleInput.isolationMode",
                        hasItem("DEDICATED_SCHEMA")))
                .andExpect(jsonPath("$.data[?(@.agentType == 'tenant-intake')].exampleInput.plan",
                        hasItem("standard")))
                .andExpect(jsonPath("$.data[?(@.agentType == 'approval')].exampleInput.riskLevel",
                        hasItem("MEDIUM")));
    }
}
