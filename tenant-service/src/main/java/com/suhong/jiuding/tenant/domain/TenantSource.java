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

/** How a tenant record entered the platform. */
public enum TenantSource {
    MANUAL,
    IMPORT,
    API,
    DISCOVERY;

    public static TenantSource parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw BizException.invalid("source is required");
        }
        try {
            return TenantSource.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw BizException.invalid("unknown source: " + raw);
        }
    }

    public static TenantSource parseOrDefault(String raw, TenantSource fallback) {
        return raw == null || raw.isBlank() ? fallback : parse(raw);
    }
}
