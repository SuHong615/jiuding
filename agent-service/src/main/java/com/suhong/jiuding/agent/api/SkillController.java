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

import com.suhong.jiuding.agent.skill.SkillRegistry;
import com.suhong.jiuding.agent.skill.SkillRunResult;
import com.suhong.jiuding.agent.skill.SkillService;
import com.suhong.jiuding.common.api.ApiResponse;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Skill execution endpoints (docs/02 §4).
 *
 * <p>Registry browsing and the skill-center lifecycle live in
 * {@link SkillCenterController}; rule execution and hot reload stay here.
 */
@RestController
@RequestMapping("/api/skills")
public class SkillController {

    private final SkillService skillService;

    public SkillController(SkillService skillService) {
        this.skillService = skillService;
    }

    @PostMapping("/reload")
    public ApiResponse<SkillRegistry.ReloadResult> reload() {
        return ApiResponse.ok(skillService.reload());
    }

    @PostMapping("/{id}/run")
    public ApiResponse<SkillRunResult> run(@PathVariable("id") String id,
                                           @RequestBody(required = false) SkillRunRequest request) {
        Map<String, Object> input = request == null ? Map.of()
                : (request.input() == null ? Map.of() : request.input());
        String tenantId = request == null ? null : TenantAccess.resolve(request.tenantId());
        return ApiResponse.ok(skillService.run(id, input, tenantId));
    }
}
