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
package com.suhong.jiuding.agent.rule;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Decision mapping block of {@code rules.yaml}.
 *
 * <p>{@code autoApproveMaxScore} / {@code rejectMinScore} map the final score to
 * a decision; {@code riskLevels} map it to a risk level. Explicit
 * {@code setDecision} / {@code setRiskLevel} actions always win. When
 * {@code emit} is {@code false} (or no decision block exists) the skill returns
 * decision {@code NONE} — used by provisioning-style skills that are not
 * decision makers.
 */
public record DecisionPolicy(int autoApproveMaxScore, int rejectMinScore,
                             List<RiskLevel> riskLevels, boolean emit) {

    public static final DecisionPolicy NONE = new DecisionPolicy(0, 100, List.of(), false);

    public DecisionPolicy {
        riskLevels = riskLevels == null ? List.of() : List.copyOf(riskLevels);
    }

    public record RiskLevel(String level, int maxScore) {
    }

    @SuppressWarnings("unchecked")
    public static DecisionPolicy from(Object node) {
        if (node == null) {
            return NONE;
        }
        if (!(node instanceof Map<?, ?> raw)) {
            throw new IllegalArgumentException("decision block must be an object");
        }
        Map<String, Object> map = (Map<String, Object>) raw;
        boolean emit = !Boolean.FALSE.equals(map.get("emit"));
        int autoApprove = asInt(map.get("autoApproveMaxScore"), 20);
        int reject = asInt(map.get("rejectMinScore"), 80);
        List<RiskLevel> levels = new ArrayList<>();
        Object rawLevels = map.get("riskLevels");
        if (rawLevels instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> levelMap) {
                    Object name = levelMap.get("level");
                    if (name != null) {
                        levels.add(new RiskLevel(String.valueOf(name).toUpperCase(java.util.Locale.ROOT),
                                asInt(levelMap.get("maxScore"), Integer.MAX_VALUE)));
                    }
                }
            }
        }
        levels.sort(java.util.Comparator.comparingInt(RiskLevel::maxScore));
        return new DecisionPolicy(autoApprove, reject, levels, emit);
    }

    private static int asInt(Object value, int fallback) {
        java.math.BigDecimal number = Operator.number(value);
        return number == null ? fallback : number.intValue();
    }
}
