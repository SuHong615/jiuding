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

import com.fasterxml.jackson.annotation.JsonInclude;
import com.suhong.jiuding.tenant.repository.OrgUnitRow;
import com.suhong.jiuding.tenant.support.TimeUtil;

import java.util.List;

/**
 * Public projection of an org unit (docs/11 section 6.2).
 *
 * <p>{@code children} is only populated for {@code format=tree} responses and is omitted
 * from flat payloads.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record OrgUnitView(
        String id,
        String tenantId,
        String parentId,
        String code,
        String name,
        String type,
        String path,
        int depth,
        int sortOrder,
        String managerAccountId,
        String managerName,
        String status,
        long accountCount,
        long childCount,
        String createdAt,
        String updatedAt,
        long version,
        List<OrgUnitView> children) {

    public static OrgUnitView of(OrgUnitRow row, String managerName, long accountCount, long childCount) {
        return new OrgUnitView(
                row.id(),
                row.tenantId(),
                row.parentId(),
                row.code(),
                row.name(),
                row.type(),
                row.path(),
                row.depth(),
                row.sortOrder(),
                row.managerAccountId(),
                managerName,
                row.status(),
                accountCount,
                childCount,
                TimeUtil.iso(row.createdAt()),
                TimeUtil.iso(row.updatedAt()),
                row.version(),
                null);
    }

    public OrgUnitView withChildren(List<OrgUnitView> children) {
        return new OrgUnitView(id, tenantId, parentId, code, name, type, path, depth, sortOrder,
                managerAccountId, managerName, status, accountCount, childCount, createdAt, updatedAt,
                version, children);
    }
}
