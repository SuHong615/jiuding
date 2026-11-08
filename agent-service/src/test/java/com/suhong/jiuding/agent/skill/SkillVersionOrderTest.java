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

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Version ordering used by {@code GET /api/skills/{id}/versions}. */
class SkillVersionOrderTest {

    @Test
    void numericSegmentsCompareNumerically() {
        assertThat(VersionOrder.compare("1.0.10", "1.0.9")).isPositive();
        assertThat(VersionOrder.compare("2.0.0", "1.99.99")).isPositive();
        assertThat(VersionOrder.compare("1.2.0", "1.2.0")).isZero();
        assertThat(VersionOrder.compare("1.0.0", "1.0.0-rc1")).isNegative();
    }

    @Test
    void descendingSortPutsNewestFirst() {
        List<String> versions = new ArrayList<>(List.of(
                "1.0.9", "1.0.10", "2.0.0-rc1", "1.0.0", "1.2.0"));
        versions.sort(VersionOrder.DESCENDING);
        assertThat(versions).containsExactly("2.0.0-rc1", "1.2.0", "1.0.10", "1.0.9", "1.0.0");
    }

    @Test
    void nullAndBlankVersionsAreStable() {
        assertThat(VersionOrder.compare(null, "")).isZero();
        assertThat(VersionOrder.compare(null, "1.0.0")).isNegative();
        assertThat(VersionOrder.compare("1.0.0", null)).isPositive();
        List<String> versions = new ArrayList<>(List.of("1.1.0", "1.0.0"));
        versions.sort(VersionOrder.DESCENDING);
        assertThat(versions).containsExactly("1.1.0", "1.0.0");
    }
}
