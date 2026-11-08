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
 * One tenant lifecycle stage of the read-only workflow view (docs/20 section 4.5).
 *
 * @param code        lifecycle state code, identical to {@code TenantStatus.name()}
 * @param name        human readable Chinese stage name
 * @param description short explanation of what happens in the stage
 * @param order       1-based stage order for the UI timeline
 */
public record WorkflowStateView(String code, String name, String description, int order) {
}
