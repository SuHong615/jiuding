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
package com.suhong.jiuding.tenant.imports;

import com.suhong.jiuding.common.error.BizException;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Minimal UTF-8 RFC-4180 style CSV reader for the import endpoints: header row plus
 * quoted fields (embedded commas / quotes / newlines are supported). Structural problems
 * are reported as {@code INVALID_ARGUMENT}; per-row business problems are handled by the
 * import service.
 */
public final class CsvImportReader {

    private CsvImportReader() {
    }

    /** Parsed CSV: header (lower cased, trimmed) plus data rows (1-based row numbers). */
    public record CsvTable(List<String> headers, List<CsvDataRow> rows) {
        public int column(String header) {
            return headers.indexOf(header.trim().toLowerCase(Locale.ROOT));
        }

        public String value(CsvDataRow row, String header) {
            int index = column(header);
            if (index < 0 || index >= row.values().size()) {
                return null;
            }
            return row.values().get(index);
        }
    }

    /** One data row; {@code rowNumber} is the physical line start (header is line 1). */
    public record CsvDataRow(int rowNumber, List<String> values) {
    }

    public static CsvTable read(byte[] content) {
        if (content == null || content.length == 0) {
            throw BizException.invalid("csv file is empty");
        }
        String text = new String(content, StandardCharsets.UTF_8);
        if (!text.isEmpty() && text.charAt(0) == '\uFEFF') {
            text = text.substring(1);
        }
        List<List<String>> rawRows = splitRows(text);
        if (rawRows.isEmpty()) {
            throw BizException.invalid("csv file is empty");
        }
        List<String> headers = new ArrayList<>();
        for (String rawHeader : rawRows.get(0)) {
            String normalized = rawHeader == null ? "" : rawHeader.trim().toLowerCase(Locale.ROOT);
            if (normalized.startsWith("\uFEFF")) {
                normalized = normalized.substring(1);
            }
            headers.add(normalized);
        }
        if (headers.stream().allMatch(String::isBlank)) {
            throw BizException.invalid("csv header is required");
        }
        List<CsvDataRow> rows = new ArrayList<>();
        for (int index = 1; index < rawRows.size(); index++) {
            rows.add(new CsvDataRow(index + 1, rawRows.get(index)));
        }
        return new CsvTable(List.copyOf(headers), List.copyOf(rows));
    }

    private static List<List<String>> splitRows(String text) {
        List<List<String>> rows = new ArrayList<>();
        List<String> currentRow = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean inQuotes = false;
        boolean rowHasContent = false;
        for (int index = 0; index < text.length(); index++) {
            char ch = text.charAt(index);
            if (inQuotes) {
                if (ch == '"') {
                    if (index + 1 < text.length() && text.charAt(index + 1) == '"') {
                        field.append('"');
                        index++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    field.append(ch);
                }
                rowHasContent = true;
                continue;
            }
            switch (ch) {
                case '"' -> {
                    inQuotes = true;
                    rowHasContent = true;
                }
                case ',' -> {
                    currentRow.add(field.toString());
                    field.setLength(0);
                    rowHasContent = true;
                }
                case '\n' -> {
                    currentRow.add(field.toString());
                    field.setLength(0);
                    addRow(rows, currentRow, rowHasContent);
                    currentRow = new ArrayList<>();
                    rowHasContent = false;
                }
                case '\r' -> {
                    // tolerate CRLF
                }
                default -> {
                    field.append(ch);
                    rowHasContent = true;
                }
            }
        }
        if (field.length() > 0 || rowHasContent || !currentRow.isEmpty()) {
            currentRow.add(field.toString());
            addRow(rows, currentRow, rowHasContent);
        }
        return rows;
    }

    private static void addRow(List<List<String>> rows, List<String> row, boolean hasContent) {
        if (!hasContent && row.isEmpty()) {
            return;
        }
        boolean blank = row.stream().allMatch(value -> value == null || value.isBlank());
        if (blank) {
            return;
        }
        rows.add(row);
    }

    /** Splits an already validated CSV value; helper for top-level callers. */
    public static List<CsvDataRow> dataRows(CsvTable table) {
        return table.rows();
    }
}
