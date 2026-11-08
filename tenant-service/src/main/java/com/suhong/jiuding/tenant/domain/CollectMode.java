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

/** Collection job type. MANUAL_BATCH and API_PULL are inline payloads, CSV is multipart. */
public enum CollectMode {
    MANUAL_BATCH,
    CSV,
    API_PULL;

    public static CollectMode parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw BizException.invalid("mode is required");
        }
        try {
            return CollectMode.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw BizException.invalid("unknown collection mode: " + raw);
        }
    }

    public static CollectMode parseOrDefault(String raw) {
        return raw == null || raw.isBlank() ? MANUAL_BATCH : parse(raw);
    }
}
