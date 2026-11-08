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

/**
 * One legal lifecycle transition edge (docs/20 section 4.5), derived from
 * {@code TenantStateMachine} so the workflow view can never drift from enforcement.
 *
 * @param from    source state code
 * @param to      target state code
 * @param trigger business trigger that causes the transition
 */
public record WorkflowTransitionView(String from, String to, String trigger) {
}
