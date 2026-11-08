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
package com.suhong.jiuding.approval.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.suhong.jiuding.common.security.JwtService;
import com.suhong.jiuding.common.security.Principal;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Shared helpers for approval-service integration tests. */
public final class TestSupport {

    private TestSupport() {
    }

    public static String bearer(JwtService jwtService, String username, Set<String> roles, String tenantId) {
        return "Bearer " + jwtService.issue(
                new Principal("u-" + username, username, tenantId, roles, "JWT"));
    }

    public static String admin(JwtService jwtService) {
        return bearer(jwtService, "admin", Set.of("PLATFORM_ADMIN"), null);
    }

    public static String approver(JwtService jwtService) {
        return bearer(jwtService, "approver", Set.of("APPROVER"), null);
    }

    public static String operator(JwtService jwtService) {
        return bearer(jwtService, "operator", Set.of("TENANT_ADMIN"), "t-acme");
    }

    public static String auditor(JwtService jwtService) {
        return bearer(jwtService, "auditor", Set.of("AUDITOR"), null);
    }

    public static String createBody(ObjectMapper mapper, String tenantId, String tenantCode,
                                    String plan, String riskLevel, Integer riskScore) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("tenantId", tenantId);
        body.put("tenantCode", tenantCode);
        body.put("plan", plan);
        body.put("riskLevel", riskLevel);
        if (riskScore != null) {
            body.put("riskScore", riskScore);
        }
        body.put("reasons", List.of("unit test reason"));
        body.put("requestedBy", "tester");
        return mapper.writeValueAsString(body);
    }

    public static JsonNode dataOf(MvcResult result, ObjectMapper mapper) throws Exception {
        JsonNode root = mapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
        return root.path("data");
    }

    public static JsonNode bodyOf(MvcResult result, ObjectMapper mapper) throws Exception {
        return mapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    public static List<String> timelineActions(JsonNode approvalData) {
        Iterable<JsonNode> timeline = approvalData.path("timeline");
        List<String> actions = new java.util.ArrayList<>();
        timeline.forEach(item -> actions.add(item.path("action").asText()));
        return actions;
    }

    public static List<String> nodeNames(JsonNode approvalData) {
        Iterable<JsonNode> nodes = approvalData.path("nodes");
        List<String> names = new java.util.ArrayList<>();
        nodes.forEach(node -> names.add(node.path("node").asText()));
        return names;
    }
}
