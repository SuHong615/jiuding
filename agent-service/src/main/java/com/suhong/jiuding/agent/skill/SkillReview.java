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

/** One row of {@code skill_reviews} (audit trail of the skill lifecycle). */
public record SkillReview(String id, String skillId, String skillVersion, String action,
                          String actor, String comment, String at) {

    public static final String ACTION_UPLOAD = "UPLOAD";
    public static final String ACTION_UPDATE = "UPDATE";
    public static final String ACTION_SUBMIT = "SUBMIT";
    public static final String ACTION_APPROVE = "APPROVE";
    public static final String ACTION_REJECT = "REJECT";
    public static final String ACTION_ENABLE = "ENABLE";
    public static final String ACTION_DISABLE = "DISABLE";
}
