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
package com.suhong.jiuding.agent.skill;

import com.suhong.jiuding.agent.agent.AgentModelFactory;
import com.suhong.jiuding.agent.repository.RuleHitRepository;
import com.suhong.jiuding.agent.rule.RuleEngine;
import com.suhong.jiuding.agent.rule.RuleSet;
import com.suhong.jiuding.common.error.BizException;
import com.suhong.jiuding.common.error.ErrorCode;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;

/**
 * Application service for deterministic skill execution.
 *
 * <p>Registry browsing / lifecycle management lives in {@link SkillCenterService};
 * this service resolves the active definition from {@link SkillRegistry} and runs
 * the rule engine (uploaded and built-in skills are treated identically).
 */
@Service
public class SkillService {

    private final SkillRegistry registry;
    private final RuleEngine ruleEngine;
    private final RuleHitRepository ruleHitRepository;
    private final AgentModelFactory modelFactory;

    public SkillService(SkillRegistry registry, RuleEngine ruleEngine,
                        RuleHitRepository ruleHitRepository, AgentModelFactory modelFactory) {
        this.registry = registry;
        this.ruleEngine = ruleEngine;
        this.ruleHitRepository = ruleHitRepository;
        this.modelFactory = modelFactory;
    }

    public SkillRegistry.ReloadResult reload() {
        return registry.reload();
    }

    public SkillDefinition require(String skillId) {
        return registry.find(skillId)
                .orElseThrow(() -> new BizException(ErrorCode.SKILL_NOT_FOUND, "skill not found: " + skillId));
    }

    /** Execute a skill by id, persisting rule hits for audit. */
    public SkillRunResult run(String skillId, Map<String, Object> input, String tenantId) {
        return runWithRunId(skillId, input, tenantId, UUID.randomUUID().toString());
    }

    /**
     * Execute a skill under a caller-supplied run id so {@code rule_hits} can be
     * correlated with the enclosing agent run.
     */
    public SkillRunResult runWithRunId(String skillId, Map<String, Object> input, String tenantId, String runId) {
        SkillDefinition definition = require(skillId);
        RuleSet rules = definition.rules();
        SkillRunResult result = ruleEngine.run(rules, input, tenantId, runId, modelFactory.label());
        ruleHitRepository.saveAll(runId, definition.id(), result.trace());
        return result;
    }
}
