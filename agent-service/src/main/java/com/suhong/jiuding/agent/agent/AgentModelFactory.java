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
package com.suhong.jiuding.agent.agent;

import com.suhong.jiuding.agent.config.TenancyAgentProperties;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.Model;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.function.Function;

/**
 * Model channel resolver.
 *
 * <p>Default is {@code scripted}: every agent has a deterministic scripted
 * responder so the whole platform is runnable and testable offline (CI, docker
 * smoke tests). When {@code tenancy.agent.model.provider=openai} and
 * {@code OPENAI_API_KEY} is present, the OpenAI-compatible channel
 * ({@code OPENAI_BASE_URL}/{@code OPENAI_MODEL}) is used instead — the agent code
 * is identical because both implement the AgentScope {@link Model} SPI.
 */
@Component
public class AgentModelFactory {

    private static final Logger log = LoggerFactory.getLogger(AgentModelFactory.class);
    private static final String SCRIPTED_LABEL = "scripted";

    private final TenancyAgentProperties properties;

    public AgentModelFactory(TenancyAgentProperties properties) {
        this.properties = properties;
        TenancyAgentProperties.Model model = properties.getAgent().getModel();
        if (openAiEnabled()) {
            log.info("agent model channel: openai-compatible (model={}, baseUrl={})",
                    model.getName(), model.getBaseUrl());
        } else {
            log.info("agent model channel: scripted (offline deterministic); provider={}",
                    model.getProvider());
        }
    }

    /** Build the model for one run: scripted responder in offline mode, otherwise the remote adapter. */
    public ResolvedModel create(Function<List<Msg>, ChatResponse> scriptedResponder) {
        TenancyAgentProperties.Model model = properties.getAgent().getModel();
        if (openAiEnabled()) {
            Model remote = new com.suhong.jiuding.agentkernel.model.OpenAiCompatibleModel(
                    model.getName(), model.getBaseUrl(), model.getApiKey(),
                    model.getTemperature(), model.getMaxTokens(),
                    Duration.ofSeconds(model.getTimeoutSeconds()));
            return new ResolvedModel(remote, model.getName(), true);
        }
        Model scripted = new com.suhong.jiuding.agentkernel.model.ScriptedModel(SCRIPTED_LABEL, scriptedResponder);
        return new ResolvedModel(scripted, SCRIPTED_LABEL, false);
    }

    /** Label recorded on skill runs / agent runs when no model was resolved. */
    public String label() {
        return openAiEnabled() ? properties.getAgent().getModel().getName() : SCRIPTED_LABEL;
    }

    public boolean openAiEnabled() {
        TenancyAgentProperties.Model model = properties.getAgent().getModel();
        return model.getProvider() != null && !model.getProvider().isBlank()
                && !"scripted".equalsIgnoreCase(model.getProvider())
                && model.getApiKey() != null && !model.getApiKey().isBlank();
    }

    /** Resolved model plus the label persisted on the run record. */
    public record ResolvedModel(Model model, String label, boolean remote) {
    }
}
