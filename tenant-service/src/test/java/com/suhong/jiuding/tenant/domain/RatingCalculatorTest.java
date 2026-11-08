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

import com.suhong.jiuding.tenant.repository.TenantRow;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class RatingCalculatorTest {

    @Test
    void gradeBoundariesFollowDocs() {
        assertThat(RatingGrade.fromScore(100)).isEqualTo(RatingGrade.AAA);
        assertThat(RatingGrade.fromScore(90)).isEqualTo(RatingGrade.AAA);
        assertThat(RatingGrade.fromScore(89)).isEqualTo(RatingGrade.AA);
        assertThat(RatingGrade.fromScore(80)).isEqualTo(RatingGrade.AA);
        assertThat(RatingGrade.fromScore(79)).isEqualTo(RatingGrade.A);
        assertThat(RatingGrade.fromScore(70)).isEqualTo(RatingGrade.A);
        assertThat(RatingGrade.fromScore(69)).isEqualTo(RatingGrade.B);
        assertThat(RatingGrade.fromScore(60)).isEqualTo(RatingGrade.B);
        assertThat(RatingGrade.fromScore(59)).isEqualTo(RatingGrade.C);
        assertThat(RatingGrade.fromScore(40)).isEqualTo(RatingGrade.C);
        assertThat(RatingGrade.fromScore(39)).isEqualTo(RatingGrade.D);
        assertThat(RatingGrade.fromScore(0)).isEqualTo(RatingGrade.D);
    }

    @Test
    void weightsAreThirtyTwentyFiveTwentyFifteenTen() {
        assertThat(RatingCalculator.withScore(new RatingCalculator.Dims(100, 100, 100, 100, 100)).score())
                .isEqualTo(100);
        assertThat(RatingCalculator.withScore(new RatingCalculator.Dims(0, 0, 0, 0, 0)).score())
                .isZero();
        assertThat(RatingCalculator.withScore(new RatingCalculator.Dims(100, 0, 0, 0, 0)).score())
                .isEqualTo(30);
        assertThat(RatingCalculator.withScore(new RatingCalculator.Dims(0, 100, 0, 0, 0)).score())
                .isEqualTo(25);
        assertThat(RatingCalculator.withScore(new RatingCalculator.Dims(0, 0, 0, 0, 100)).score())
                .isEqualTo(10);
        assertThat(RatingCalculator.withScore(new RatingCalculator.Dims(60, 60, 60, 60, 60)).grade())
                .isEqualTo(RatingGrade.B);
    }

    @Test
    void calculationIsDeterministicForIdenticalInputs() {
        TenantRow first = tenant("t-one", "PRO", "LOW", 10);
        TenantRow second = tenant("t-two", "PRO", "LOW", 10);
        RatingCalculator.Result one = RatingCalculator.calculate(first, 3, 7);
        RatingCalculator.Result two = RatingCalculator.calculate(second, 3, 7);
        assertThat(one.score()).isEqualTo(two.score());
        assertThat(one.grade()).isEqualTo(two.grade());
        assertThat(one.dims()).isEqualTo(two.dims());
    }

    @Test
    void skillAdjustmentIsBoundedAndRecomputesGrade() {
        RatingCalculator.Dims dims = new RatingCalculator.Dims(70, 70, 70, 70, 70);
        int base = RatingCalculator.withScore(dims).score();
        assertThat(RatingCalculator.adjust(dims, 100).score()).isEqualTo(Math.min(100, base + 15));
        assertThat(RatingCalculator.adjust(dims, -100).score()).isEqualTo(base - 15);
        assertThat(RatingCalculator.adjust(dims, 0).score()).isEqualTo(base);
    }

    @Test
    void highRiskAndBlacklistLowerCompliance() {
        RatingCalculator.Result lowRisk = RatingCalculator.calculate(tenant("a", "PRO", "LOW", 5), 0, 0);
        RatingCalculator.Result highRisk = RatingCalculator.calculate(tenant("b", "PRO", "HIGH", 85), 0, 0);
        assertThat(highRisk.dims().compliance()).isLessThan(lowRisk.dims().compliance());
        assertThat(highRisk.score()).isLessThan(lowRisk.score());
    }

    private TenantRow tenant(String code, String plan, String riskLevel, int riskScore) {
        LocalDateTime now = LocalDateTime.now();
        return new TenantRow(
                "id-" + code, code, "Name " + code, null, code + "@acme.io", null,
                plan, "cn-shanghai", "SHARED_SCHEMA", TenantStatus.DRAFT.name(),
                riskLevel, riskScore, 100, 100, 1000, "MANUAL", null, null,
                now, now, null, null, null, null, null, null, null, 0L);
    }
}
