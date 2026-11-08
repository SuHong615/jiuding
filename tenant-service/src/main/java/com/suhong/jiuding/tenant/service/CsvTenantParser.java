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
package com.suhong.jiuding.tenant.service;

import com.suhong.jiuding.common.error.BizException;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Lenient RFC-4180 style CSV parser for tenant collection.
 *
 * <p>Contract: UTF-8 (optional BOM), first line is the header and must contain
 * {@code tenantCode} and {@code name}. Supported optional columns: contactName,
 * contactEmail, contactPhone, plan, region, isolationMode, quotaSeats,
 * quotaStorageGb, quotaApiRpm, source. Unknown columns are ignored. Structural
 * problems throw {@code INVALID_ARGUMENT}; row level problems are reported per
 * row so the collection job can count them.
 */
@Component
public class CsvTenantParser {

    private static final Set<String> REQUIRED_HEADERS = Set.of("tenantcode", "name");
    private static final Set<String> KNOWN_HEADERS = Set.of(
            "tenantcode", "name", "contactname", "contactemail", "contactphone",
            "plan", "region", "isolationmode", "quotaseats", "quotastoragegb",
            "quotapirm", "source");

    public CsvDocument parse(byte[] content, long maxBytes) {
        if (content == null || content.length == 0) {
            throw BizException.invalid("csv file is empty");
        }
        if (content.length > maxBytes) {
            throw BizException.invalid("csv file exceeds the " + maxBytes + " byte limit");
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
        for (String cell : rawRows.get(0)) {
            String normalized = cell.trim().toLowerCase(Locale.ROOT);
            if (normalized.startsWith("\uFEFF")) {
                normalized = normalized.substring(1);
            }
            headers.add(normalized);
        }
        if (headers.stream().allMatch(String::isBlank)) {
            throw BizException.invalid("csv header is required");
        }
        for (String required : REQUIRED_HEADERS) {
            if (!headers.contains(required)) {
                throw BizException.invalid("csv header must contain tenantCode and name columns");
            }
        }

        List<CsvRow> rows = new ArrayList<>();
        for (int index = 1; index < rawRows.size(); index++) {
            List<String> cells = rawRows.get(index);
            int line = index + 1;
            if (cells.size() > headers.size()) {
                rows.add(new CsvRow(line, Map.of(),
                        "expected " + headers.size() + " columns but found " + cells.size()));
                continue;
            }
            Map<String, String> values = new LinkedHashMap<>();
            for (int column = 0; column < cells.size() && column < headers.size(); column++) {
                String header = headers.get(column);
                if (header == null || header.isBlank() || !KNOWN_HEADERS.contains(header)) {
                    continue;
                }
                values.put(header, cells.get(column));
            }
            rows.add(new CsvRow(line, Map.copyOf(values), null));
        }
        return new CsvDocument(List.copyOf(rows));
    }

    private List<List<String>> splitRows(String text) {
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
                    // tolerate CRLF / CR line endings
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

    private void addRow(List<List<String>> rows, List<String> row, boolean hasContent) {
        if (!hasContent && row.isEmpty()) {
            return;
        }
        boolean blank = row.stream().allMatch(value -> value == null || value.isBlank());
        if (blank) {
            return;
        }
        rows.add(row);
    }

    /** One physical CSV data row; {@code error} is non-null when the row is structurally invalid. */
    public record CsvRow(int line, Map<String, String> values, String error) {
    }

    /** Parsed CSV document: ordered data rows (header excluded). */
    public record CsvDocument(List<CsvRow> rows) {
    }
}
