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
package com.suhong.jiuding.tenant.quota;

/**
 * Thrown when a create operation would exceed a tenant quota (docs/17 section 4).
 * Mapped to HTTP 409 with the shared {@code QUOTA_EXCEEDED} error code.
 */
public class QuotaExceededException extends RuntimeException {

    private final String resource;
    private final long used;
    private final int limit;

    public QuotaExceededException(String resource, long used, int limit) {
        super("QUOTA_EXCEEDED: " + resource + " quota exceeded (used=" + used + ", limit=" + limit + ")");
        this.resource = resource;
        this.used = used;
        this.limit = limit;
    }

    public String resource() {
        return resource;
    }

    public long used() {
        return used;
    }

    public int limit() {
        return limit;
    }
}
