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
package com.suhong.jiuding.approval.workflowdef;

import java.util.List;

/** Result of {@code POST /api/approval-workflows/{id}/validate}. */
public record WorkflowValidationResult(boolean valid, List<String> errors, List<String> warnings, int nodeCount) {

    public static WorkflowValidationResult ok(List<String> warnings, int nodeCount) {
        return new WorkflowValidationResult(true, List.of(), List.copyOf(warnings), nodeCount);
    }

    public static WorkflowValidationResult failed(List<String> errors, List<String> warnings, int nodeCount) {
        return new WorkflowValidationResult(false, List.copyOf(errors), List.copyOf(warnings), nodeCount);
    }

    public String summary() {
        return errors.isEmpty() ? "valid" : String.join("; ", errors);
    }
}
