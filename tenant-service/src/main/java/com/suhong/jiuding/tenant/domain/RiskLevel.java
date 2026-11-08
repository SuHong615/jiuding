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
package com.suhong.jiuding.tenant.domain;

import com.suhong.jiuding.common.error.BizException;

import java.util.Locale;

/** Risk bucket assigned by the risk-review skill / approval-service. */
public enum RiskLevel {
    LOW,
    MEDIUM,
    HIGH;

    public static RiskLevel parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw BizException.invalid("riskLevel is required");
        }
        try {
            return RiskLevel.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw BizException.invalid("unknown riskLevel: " + raw + " (expected LOW|MEDIUM|HIGH)");
        }
    }

    /** @return true when the raw value is null/blank or a known level. */
    public static boolean isValidOrNull(String raw) {
        if (raw == null || raw.isBlank()) {
            return true;
        }
        try {
            RiskLevel.valueOf(raw.trim().toUpperCase(Locale.ROOT));
            return true;
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }
}
