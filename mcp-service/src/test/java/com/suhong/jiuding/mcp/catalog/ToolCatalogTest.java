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
package com.suhong.jiuding.mcp.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Tool / resource / prompt catalogs must match docs/05 exactly. */
@SpringBootTest(properties = "tenancy.security.api-keys=test-key")
@AutoConfigureMockMvc
class ToolCatalogTest {

    private static final List<String> EXPECTED_TOOLS = List.of(
            "tenant.create", "tenant.list", "tenant.get", "tenant.submit", "tenant.suspend", "tenant.activate",
            "collection.import", "approval.list_pending", "approval.get", "approval.decide",
            "skill.list", "skill.run", "agent.run", "platform.overview",
            "org.tree", "org.create", "account.list", "account.create", "account.reset_password",
            "rating.assess", "rating.list", "tenant.delete");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void toolsListReturnsExactlyTwentyTwoToolsWithSchemas() throws Exception {
        MvcResult result = mockMvc.perform(post("/mcp")
                        .header("X-API-Key", "test-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode tools = objectMapper.readTree(result.getResponse().getContentAsString())
                .path("result").path("tools");
        assertThat(tools.size()).isEqualTo(22);

        Map<String, JsonNode> byName = new LinkedHashMap<>();
        tools.forEach(tool -> {
            byName.put(tool.path("name").asText(), tool);
            assertThat(tool.path("description").asText()).isNotBlank();
            assertThat(tool.path("inputSchema").path("type").asText()).isEqualTo("object");
        });
        assertThat(byName.keySet()).containsExactlyInAnyOrderElementsOf(EXPECTED_TOOLS);

        assertThat(requiredFields(byName.get("tenant.create"))).contains("tenantCode", "name");
        assertThat(requiredFields(byName.get("tenant.get"))).contains("tenantId");
        assertThat(requiredFields(byName.get("tenant.submit"))).contains("tenantId");
        assertThat(requiredFields(byName.get("approval.decide"))).contains("approvalId", "decision");
        assertThat(requiredFields(byName.get("skill.run"))).contains("skillId");
        assertThat(requiredFields(byName.get("agent.run"))).contains("agentType");
        assertThat(requiredFields(byName.get("collection.import"))).contains("sourceName");

        // docs/11 §6 identity extensions
        assertThat(requiredFields(byName.get("org.tree"))).contains("tenantId");
        assertThat(requiredFields(byName.get("org.create"))).contains("tenantId", "code", "name", "type");
        assertThat(requiredFields(byName.get("account.list"))).contains("tenantId");
        assertThat(requiredFields(byName.get("account.create")))
                .contains("tenantId", "username", "displayName", "role");
        assertThat(requiredFields(byName.get("account.reset_password"))).contains("tenantId", "accountId");
        assertThat(requiredFields(byName.get("rating.assess"))).contains("tenantId");
        assertThat(requiredFields(byName.get("rating.list"))).contains("tenantId");
        assertThat(requiredFields(byName.get("tenant.delete"))).contains("tenantId");

        List<String> orgTypes = enumValues(byName.get("org.create"), "type");
        assertThat(orgTypes).containsExactly("COMPANY", "BRANCH", "DEPARTMENT", "TEAM");
        List<String> formats = enumValues(byName.get("org.tree"), "format");
        assertThat(formats).containsExactly("tree", "flat");
    }

    @Test
    void mcpInfoSnapshotDescribesServerCapabilities() throws Exception {
        MvcResult result = mockMvc.perform(get("/mcp/info").header("X-API-Key", "test-key"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode info = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(info.path("server").path("name").asText()).isEqualTo("tenancy-mcp");
        assertThat(info.path("protocolVersion").asText()).isEqualTo("2025-06-18");
        assertThat(info.path("toolCount").asInt()).isEqualTo(22);
        assertThat(info.path("tools").size()).isEqualTo(22);
        assertThat(info.path("resources").size()).isEqualTo(4);
        assertThat(info.path("prompts").size()).isEqualTo(2);
        assertThat(info.path("apiKeyRequired").asBoolean()).isTrue();
        assertThat(info.path("endpoints").path("rpc").asText()).isEqualTo("POST /mcp");
    }

    @Test
    void resourcesListHasFourEntries() throws Exception {
        MvcResult result = mockMvc.perform(post("/mcp")
                        .header("X-API-Key", "test-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"resources/list\"}"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode resources = objectMapper.readTree(result.getResponse().getContentAsString())
                .path("result").path("resources");
        assertThat(resources.size()).isEqualTo(4);
        List<String> uris = new java.util.ArrayList<>();
        resources.forEach(resource -> uris.add(resource.path("uri").asText()));
        assertThat(uris).containsExactly(
                "tenancy://tenants",
                "tenancy://tenants/{id}",
                "tenancy://skills",
                "tenancy://approvals/pending");
    }

    @Test
    void promptsListHasTwoEntries() throws Exception {
        MvcResult result = mockMvc.perform(post("/mcp")
                        .header("X-API-Key", "test-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"prompts/list\"}"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode prompts = objectMapper.readTree(result.getResponse().getContentAsString())
                .path("result").path("prompts");
        assertThat(prompts.size()).isEqualTo(2);
        assertThat(prompts.get(0).path("name").asText()).isEqualTo("tenant_review");
        assertThat(prompts.get(0).path("arguments").get(0).path("name").asText()).isEqualTo("tenantId");
        assertThat(prompts.get(1).path("name").asText()).isEqualTo("approval_brief");
    }

    private List<String> requiredFields(JsonNode tool) {
        List<String> required = new java.util.ArrayList<>();
        tool.path("inputSchema").path("required").forEach(node -> required.add(node.asText()));
        return required;
    }

    private List<String> enumValues(JsonNode tool, String field) {
        List<String> values = new java.util.ArrayList<>();
        tool.path("inputSchema").path("properties").path(field).path("enum")
                .forEach(node -> values.add(node.asText()));
        return values;
    }
}
