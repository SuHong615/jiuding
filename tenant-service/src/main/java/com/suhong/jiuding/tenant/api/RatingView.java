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
package com.suhong.jiuding.tenant.api;

import com.suhong.jiuding.tenant.repository.RatingRow;
import com.suhong.jiuding.tenant.support.TimeUtil;

/**
 * Public projection of one rating history row (docs/11 section 6.4, docs/14 section 3.3).
 *
 * <p>The delta fields compare the row with the previous (older) assessment:
 * {@code previousGrade}/{@code previousScore} are null for the first assessment and
 * {@code scoreDelta} is then 0 while {@code gradeChanged} is false.
 */
public record RatingView(
        String id,
        String tenantId,
        String grade,
        int score,
        String previousGrade,
        Integer previousScore,
        int scoreDelta,
        boolean gradeChanged,
        Dims dims,
        String source,
        String assessedBy,
        String note,
        String createdAt) {

    public static RatingView of(RatingRow row) {
        return of(row, (RatingRow) null);
    }

    /** @param previous the older assessment row, may be {@code null}. */
    public static RatingView of(RatingRow row, RatingRow previous) {
        return of(row,
                previous == null ? null : previous.grade(),
                previous == null ? null : previous.score());
    }

    public static RatingView of(RatingRow row, String previousGrade, Integer previousScore) {
        return new RatingView(
                row.id(),
                row.tenantId(),
                row.grade(),
                row.score(),
                previousGrade,
                previousScore,
                previousScore == null ? 0 : row.score() - previousScore,
                previousGrade != null && !previousGrade.equals(row.grade()),
                new Dims(row.dimBusiness(), row.dimCompliance(), row.dimFinancial(),
                        row.dimSecurity(), row.dimService()),
                row.source(),
                row.assessedBy(),
                row.note(),
                TimeUtil.iso(row.createdAt()));
    }

    /** Five dimension scores, mirroring the docs/11 section 4 weights. */
    public record Dims(int business, int compliance, int financial, int security, int service) {
    }
}
