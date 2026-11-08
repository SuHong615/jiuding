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

import java.util.List;

/**
 * Agent run projection (docs/02 §4 + docs/15 §3 readability fields).
 *
 * <p>{@code displayName/purpose/summary} plus the persisted input/tenant are
 * filled by the service layer from the run record and the agent catalog.
 */
public record AgentRunView(String runId, String agentType, String displayName, String purpose,
                           String status, Object output, String summary, List<Step> steps,
                           String model, int tokensUsed, long durationMs, String createdAt,
                           String tenantId, Object input) {
}
