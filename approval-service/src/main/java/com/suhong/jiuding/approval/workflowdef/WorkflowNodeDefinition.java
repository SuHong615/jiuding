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
import java.util.Locale;

/**
 * Parsed representation of one node of a workflow definition JSON.
 *
 * <p>Supported types (docs/23 section 3): {@code AUTO_CHECK}, {@code HUMAN},
 * {@code PARALLEL} (executed sequentially in v12) and {@code SLA} (sets the SLA
 * for the next human node).
 */
public record WorkflowNodeDefinition(
        String id,
        String type,
        String skill,
        String role,
        Integer slaHours,
        String next,
        List<WorkflowNodeDefinition> children) {

    public static final String TYPE_AUTO_CHECK = "AUTO_CHECK";
    public static final String TYPE_HUMAN = "HUMAN";
    public static final String TYPE_PARALLEL = "PARALLEL";
    public static final String TYPE_SLA = "SLA";

    public String normalizedType() {
        return type == null ? null : type.trim().toUpperCase(Locale.ROOT);
    }

    public boolean isHuman() {
        return TYPE_HUMAN.equals(normalizedType());
    }

    public Integer effectiveSlaHours() {
        return slaHours;
    }

    public String effectiveRole(String fallback) {
        return role == null || role.isBlank() ? fallback : role;
    }

    public String effectiveSkill(String fallback) {
        return skill == null || skill.isBlank() ? fallback : skill;
    }
}
