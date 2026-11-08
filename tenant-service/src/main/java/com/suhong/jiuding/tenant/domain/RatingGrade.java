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

/**
 * Tenant rating grade (docs/11 section 4):
 * AAA &gt;= 90, AA 80-89, A 70-79, B 60-69, C 40-59, D &lt; 40.
 */
public enum RatingGrade {
    AAA,
    AA,
    A,
    B,
    C,
    D;

    public static RatingGrade fromScore(int score) {
        if (score >= 90) {
            return AAA;
        }
        if (score >= 80) {
            return AA;
        }
        if (score >= 70) {
            return A;
        }
        if (score >= 60) {
            return B;
        }
        if (score >= 40) {
            return C;
        }
        return D;
    }

    public static RatingGrade parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw BizException.invalid("rating grade is required (AAA|AA|A|B|C|D)");
        }
        try {
            return RatingGrade.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw BizException.invalid("unknown rating grade: " + raw + " (expected AAA|AA|A|B|C|D)");
        }
    }
}
