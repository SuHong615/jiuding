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
package com.suhong.jiuding.cli;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Table rendering: CJK-aware width, truncation, null handling, alignment. */
class TableRendererTest {

    @Test
    void cjkCharactersCountAsTwoCells() {
        assertEquals(4, TableRenderer.displayWidth("中文"));
        assertEquals(7, TableRenderer.displayWidth("abc中文"));
        assertEquals(2, TableRenderer.displayWidth("AB"));
        assertEquals(0, TableRenderer.displayWidth(""));
        assertEquals(0, TableRenderer.displayWidth(null));
    }

    @Test
    void everyRenderedLineHasSameDisplayWidth() {
        String table = TableRenderer.render(
                List.of("名称", "ID", "状态"),
                List.of(
                        row("中文名称", "t-001", "ACTIVE"),
                        row("english-name", "t-002", "SUSPENDED"),
                        row("混合 mixed 名称", "t-003", "DRAFT")));
        int expected = TableRenderer.displayWidth(table.lines().findFirst().orElseThrow());
        table.lines().forEach(line ->
                assertEquals(expected, TableRenderer.displayWidth(line), "misaligned line: " + line));
    }

    @Test
    void longCellsAreTruncatedToTheColumnBudget() {
        String table = TableRenderer.render(
                List.of("DESCRIPTION"),
                List.of(row("a".repeat(120))), 20);
        assertTrue(table.contains("..."), "truncated cell should end with ellipsis");
        table.lines().forEach(line -> assertTrue(TableRenderer.displayWidth(line) <= 24,
                "line too wide: " + line));
    }

    @Test
    void nullAndMultilineCellsAreSanitized() {
        List<String> tableRow = BaseCommand.row(null, "line1\nline2", "a\tb");
        assertEquals("-", tableRow.get(0));
        String table = TableRenderer.render(List.of("A", "B", "C"), List.of(tableRow));
        assertTrue(table.contains("line1 line2"));
        assertFalse(table.contains("line1\nline2"));
        assertTrue(table.contains("a b"));
    }

    @Test
    void detailTableHasTwoColumns() {
        String detail = TableRenderer.detail(List.of("字段A", "字段B"), List.of("值1", "值2"));
        assertTrue(detail.contains("字段A"));
        assertTrue(detail.contains("值1"));
        assertTrue(detail.contains("字段"));
        assertFalse(detail.isBlank());
    }

    @Test
    void emptyRowsStillRenderHeader() {
        String table = TableRenderer.render(List.of("ID", "名称"), List.of());
        assertTrue(table.contains("ID"));
        assertTrue(table.contains("名称"));
        assertEquals(3, table.lines().count(), "top border + header + bottom border expected");
    }

    @Test
    void truncateKeepsShortStringsUntouched() {
        assertEquals("短期", TableRenderer.truncate("短期", 10));
        assertEquals("abc", TableRenderer.truncate("abc", 3));
        assertTrue(TableRenderer.truncate("abcdefgh", 5).endsWith("..."));
    }

    private static List<String> row(String... cells) {
        return BaseCommand.row(cells);
    }
}
