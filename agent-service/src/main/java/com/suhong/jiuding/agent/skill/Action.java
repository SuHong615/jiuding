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

import java.util.Map;

/**
 * An executed rule action, surfaced to consumers (approval-service, MCP, UI).
 *
 * <p>Shape: {@code {"ruleId":"R-...","action":"requireRole","value":"L1","message":"..."}}.
 */
public record Action(String ruleId, String action, Object value, String message) {
}
