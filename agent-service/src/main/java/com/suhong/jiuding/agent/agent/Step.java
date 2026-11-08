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

/**
 * One ReAct step (docs/02 §4, docs/15 §3: adds Chinese-readable title/label).
 *
 * <p>New fields are appended so rows persisted before v5 still deserialize.
 */
public record Step(int index, String thought, String action, String actionInput, String observation,
                   String title, String actionLabel) {

    public Step(int index, String thought, String action, String actionInput, String observation) {
        this(index, thought, action, actionInput, observation, null, null);
    }
}
