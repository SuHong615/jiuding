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

import java.util.ArrayList;
import java.util.List;

/**
 * Renders aligned ASCII tables. Column widths use "display width": CJK characters
 * count as two cells so Chinese and English columns line up in a monospace terminal.
 */
public final class TableRenderer {

    public static final int DEFAULT_MAX_COLUMN_WIDTH = 48;
    private static final String ELLIPSIS = "...";

    private TableRenderer() {
    }

    public static String render(List<String> headers, List<List<String>> rows) {
        return render(headers, rows, DEFAULT_MAX_COLUMN_WIDTH);
    }

    public static String render(List<String> headers, List<List<String>> rows, int maxColumnWidth) {
        if (headers == null || headers.isEmpty()) {
            return "";
        }
        int columns = headers.size();
        List<String> safeHeaders = new ArrayList<>(columns);
        for (String header : headers) {
            safeHeaders.add(sanitize(header));
        }
        List<List<String>> safeRows = new ArrayList<>();
        if (rows != null) {
            for (List<String> row : rows) {
                List<String> cells = new ArrayList<>(columns);
                for (int i = 0; i < columns; i++) {
                    String cell = row != null && i < row.size() ? row.get(i) : "";
                    cells.add(sanitize(cell));
                }
                safeRows.add(cells);
            }
        }

        int[] widths = new int[columns];
        for (int i = 0; i < columns; i++) {
            widths[i] = displayWidth(safeHeaders.get(i));
        }
        for (List<String> row : safeRows) {
            for (int i = 0; i < columns; i++) {
                widths[i] = Math.max(widths[i], displayWidth(row.get(i)));
            }
        }
        for (int i = 0; i < columns; i++) {
            widths[i] = Math.min(widths[i], Math.max(1, maxColumnWidth));
        }

        String border = border(widths);
        StringBuilder sb = new StringBuilder();
        sb.append(border).append('\n');
        sb.append(rowLine(safeHeaders, widths)).append('\n');
        sb.append(border);
        for (List<String> row : safeRows) {
            sb.append('\n').append(rowLine(row, widths));
        }
        if (!safeRows.isEmpty()) {
            sb.append('\n').append(border);
        }
        return sb.toString();
    }

    /** Renders a two-column "field / value" table for a single object. */
    public static String detail(List<String> labels, List<String> values) {
        List<String> headers = List.of("字段", "值");
        List<List<String>> rows = new ArrayList<>();
        for (int i = 0; i < labels.size(); i++) {
            rows.add(List.of(labels.get(i), i < values.size() && values.get(i) != null ? values.get(i) : "-"));
        }
        return render(headers, rows);
    }

    /** Terminal display width: CJK / full-width characters take two cells. */
    public static int displayWidth(String value) {
        if (value == null || value.isEmpty()) {
            return 0;
        }
        int width = 0;
        for (int i = 0; i < value.length(); ) {
            int codePoint = value.codePointAt(i);
            width += isWide(codePoint) ? 2 : 1;
            i += Character.charCount(codePoint);
        }
        return width;
    }

    /** Truncates a string to a display width, appending "..." when shortened. */
    public static String truncate(String value, int maxWidth) {
        String text = sanitize(value);
        if (maxWidth <= 0 || displayWidth(text) <= maxWidth) {
            return text;
        }
        int budget = Math.max(0, maxWidth - ELLIPSIS.length());
        StringBuilder sb = new StringBuilder();
        int width = 0;
        for (int i = 0; i < text.length(); ) {
            int codePoint = text.codePointAt(i);
            int cell = isWide(codePoint) ? 2 : 1;
            if (width + cell > budget) {
                break;
            }
            sb.appendCodePoint(codePoint);
            width += cell;
            i += Character.charCount(codePoint);
        }
        return sb + ELLIPSIS;
    }

    static boolean isWide(int codePoint) {
        return (codePoint >= 0x1100 && codePoint <= 0x115F)   // Hangul Jamo
                || (codePoint >= 0x2E80 && codePoint <= 0x303E) // CJK radicals, punctuation
                || (codePoint >= 0x3041 && codePoint <= 0x33FF) // Hiragana/Katakana/CJK compat
                || (codePoint >= 0x3400 && codePoint <= 0x4DBF) // CJK ext A
                || (codePoint >= 0x4E00 && codePoint <= 0x9FFF) // CJK unified
                || (codePoint >= 0xA000 && codePoint <= 0xA4CF) // Yi
                || (codePoint >= 0xAC00 && codePoint <= 0xD7A3) // Hangul syllables
                || (codePoint >= 0xF900 && codePoint <= 0xFAFF) // CJK compat ideographs
                || (codePoint >= 0xFE30 && codePoint <= 0xFE4F) // CJK compat forms
                || (codePoint >= 0xFF00 && codePoint <= 0xFF60) // full-width forms
                || (codePoint >= 0xFFE0 && codePoint <= 0xFFE6) // full-width signs
                || (codePoint >= 0x1F300 && codePoint <= 0x1FAFF) // emoji (wide in terminals)
                || (codePoint >= 0x20000 && codePoint <= 0x3FFFD); // CJK ext B+
    }

    private static String sanitize(String value) {
        if (value == null) {
            return "-";
        }
        return value.replace("\r", " ").replace("\n", " ").replace("\t", " ");
    }

    private static String border(int[] widths) {
        StringBuilder sb = new StringBuilder("+");
        for (int width : widths) {
            sb.append("-".repeat(width + 2)).append('+');
        }
        return sb.toString();
    }

    private static String rowLine(List<String> cells, int[] widths) {
        StringBuilder sb = new StringBuilder("|");
        for (int i = 0; i < widths.length; i++) {
            String cell = i < cells.size() ? cells.get(i) : "";
            String shown = truncate(cell, widths[i]);
            sb.append(' ').append(shown)
                    .append(" ".repeat(Math.max(0, widths[i] - displayWidth(shown))))
                    .append(" |");
        }
        return sb.toString();
    }
}
