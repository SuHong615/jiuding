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

/** Physical isolation strategy of a tenant. DEDICATED_* modes are recorded only (prototype). */
public enum IsolationMode {
    SHARED_SCHEMA,
    DEDICATED_SCHEMA,
    DEDICATED_DB;

    public static IsolationMode parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw BizException.invalid("isolationMode is required");
        }
        try {
            return IsolationMode.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw BizException.invalid("unknown isolationMode: " + raw);
        }
    }

    public static IsolationMode parseOrDefault(String raw) {
        return raw == null || raw.isBlank() ? SHARED_SCHEMA : parse(raw);
    }
}
