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

import com.suhong.jiuding.agent.agent.AgentPresentation;
import com.suhong.jiuding.agent.agent.AgentRunService;
import com.suhong.jiuding.agent.agent.AgentRunView;
import com.suhong.jiuding.agent.agent.ToolDescriptor;
import com.suhong.jiuding.common.api.ApiResponse;
import com.suhong.jiuding.common.api.PageResult;
import com.suhong.jiuding.common.error.BizException;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** Agent runtime endpoints (docs/02 §4). */
@RestController
@RequestMapping("/api/agents")
public class AgentController {

    private final AgentRunService agentRunService;

    public AgentController(AgentRunService agentRunService) {
        this.agentRunService = agentRunService;
    }

    @PostMapping("/runs")
    public ApiResponse<AgentRunView> run(@Valid @RequestBody AgentRunRequest request) {
        String tenantId = TenantAccess.resolve(request.tenantId());
        AgentRunView view = agentRunService.run(request.agentType(), tenantId, request.input(),
                request.maxSteps());
        return ApiResponse.ok(view);
    }

    @GetMapping("/runs/{runId}")
    public ApiResponse<AgentRunView> get(@PathVariable("runId") String runId) {
        AgentRunView view = agentRunService.find(runId)
                .orElseThrow(() -> BizException.notFound("agent run not found: " + runId));
        return ApiResponse.ok(view);
    }

    @GetMapping("/runs")
    public ApiResponse<PageResult<AgentRunView>> page(
            @RequestParam(value = "agentType", required = false) String agentType,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "20") int size) {
        return ApiResponse.ok(agentRunService.page(agentType, page, size));
    }

    @GetMapping("/catalog")
    public ApiResponse<List<AgentPresentation.CatalogEntry>> catalog() {
        return ApiResponse.ok(agentRunService.catalog());
    }

    @GetMapping("/tools")
    public ApiResponse<List<ToolDescriptor>> tools() {
        return ApiResponse.ok(agentRunService.tools());
    }

    @GetMapping("/overview")
    public ApiResponse<Map<String, Object>> overview() {
        return ApiResponse.ok(agentRunService.overview());
    }
}
