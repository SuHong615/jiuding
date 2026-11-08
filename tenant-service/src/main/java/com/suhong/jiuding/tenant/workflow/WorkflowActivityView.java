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
package com.suhong.jiuding.tenant.workflow;

import java.util.List;

/**
 * One activity executed while a tenant is in a lifecycle stage (docs/20 section 4.5).
 *
 * <p>{@code skillId} / {@code skillName} map to the skill packages under {@code skills/};
 * {@code agentType} is the agent-service agent family responsible for the activity
 * ({@code tenant-intake}, {@code risk-review}, {@code approval}, {@code provisioning}) or
 * the skill identifier when the skill is invoked directly by tenant-service
 * ({@code tenant-rating}, docs/11 section 4).
 *
 * @param state     lifecycle state code this activity belongs to
 * @param activity  human readable activity name
 * @param skillId   skill package id
 * @param skillName skill display name from the SKILL.md frontmatter
 * @param agentType responsible agent family / direct skill executor
 * @param outputs   structured outputs produced by the activity
 * @param sla       expected turnaround / SLA window
 */
public record WorkflowActivityView(String state,
                                   String activity,
                                   String skillId,
                                   String skillName,
                                   String agentType,
                                   List<String> outputs,
                                   String sla) {
}
