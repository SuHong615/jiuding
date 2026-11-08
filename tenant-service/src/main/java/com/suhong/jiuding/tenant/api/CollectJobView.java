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
package com.suhong.jiuding.tenant.api;

import com.suhong.jiuding.tenant.repository.CollectJobRow;
import com.suhong.jiuding.tenant.support.TimeUtil;

import java.util.List;

/** Public projection of a collection job. */
public record CollectJobView(
        String id,
        String mode,
        String sourceName,
        String status,
        int total,
        int imported,
        int duplicated,
        int failed,
        List<String> errors,
        String createdAt,
        String finishedAt) {

    public static CollectJobView of(CollectJobRow row) {
        return new CollectJobView(
                row.id(),
                row.mode(),
                row.sourceName(),
                row.status(),
                row.total(),
                row.imported(),
                row.duplicated(),
                row.failed(),
                row.errors(),
                TimeUtil.iso(row.createdAt()),
                TimeUtil.iso(row.finishedAt()));
    }
}
