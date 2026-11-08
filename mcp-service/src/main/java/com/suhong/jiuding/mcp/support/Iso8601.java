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
package com.suhong.jiuding.mcp.support;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/** ISO-8601 UTC formatting for API responses (shared tiny helper for the MCP module). */
public final class Iso8601 {

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ISO_INSTANT;

    private Iso8601() {
    }

    public static String format(LocalDateTime value) {
        return value == null ? null : FORMATTER.format(value.toInstant(ZoneOffset.UTC));
    }
}
