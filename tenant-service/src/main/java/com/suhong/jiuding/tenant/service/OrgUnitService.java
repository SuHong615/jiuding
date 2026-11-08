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
import com.suhong.jiuding.common.tenant.TenantScope;
import com.suhong.jiuding.tenant.api.OrgUnitCreateRequest;
import com.suhong.jiuding.tenant.api.OrgUnitMoveRequest;
import com.suhong.jiuding.tenant.api.OrgUnitUpdateRequest;
import com.suhong.jiuding.tenant.api.OrgUnitView;
import com.suhong.jiuding.tenant.domain.OrgUnitStatus;
import com.suhong.jiuding.tenant.domain.OrgUnitType;
import com.suhong.jiuding.tenant.quota.QuotaGuard;
import com.suhong.jiuding.tenant.repository.AccountRepository;
import com.suhong.jiuding.tenant.repository.OrgUnitRepository;
import com.suhong.jiuding.tenant.repository.OrgUnitRow;
import com.suhong.jiuding.tenant.repository.TenantRow;
import com.suhong.jiuding.tenant.support.TenantAccess;
import com.suhong.jiuding.tenant.support.Texts;
import com.suhong.jiuding.tenant.support.TimeUtil;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Organisation unit tree (docs/11 section 6.2).
 *
 * <p>Materialised path + adjacency list: a new child is {@code parent.path + "/" + id},
 * moving a node rewrites the paths/depths of the whole subtree inside one transaction.
 * Depth is capped at 6 (root depth 0), codes are unique per tenant and moves that would
 * create a cycle are rejected.
 */
@Service
public class OrgUnitService {

    private static final int MAX_DEPTH = 6;

    private final OrgUnitRepository orgUnits;
    private final AccountRepository accounts;
    private final TenantAccess access;
    private final AuditService auditService;
    private final QuotaGuard quotaGuard;

    public OrgUnitService(OrgUnitRepository orgUnits,
                          AccountRepository accounts,
                          TenantAccess access,
                          AuditService auditService,
                          QuotaGuard quotaGuard) {
        this.orgUnits = orgUnits;
        this.accounts = accounts;
        this.access = access;
        this.auditService = auditService;
        this.quotaGuard = quotaGuard;
    }

    // ------------------------------------------------------------------ queries

    public List<OrgUnitView> list(String tenantId, String format, String status, String keyword) {
        TenantScope scope = access.requireScope();
        TenantRow tenant = scopedTenant(scope, tenantId);
        String resolvedStatus = status == null || status.isBlank()
                ? null : OrgUnitStatus.parse(status).name();
        String resolvedKeyword = keyword == null || keyword.isBlank() ? null : keyword.trim();
        String resolvedFormat = format == null || format.isBlank() ? "tree" : format.trim().toLowerCase();

        List<OrgUnitRow> rows = orgUnits.list(tenant.id(), resolvedStatus, resolvedKeyword);
        if (access.isOrgScoped(scope)) {
            // ORG_ADMIN / MEMBER only ever see their own subtree.
            OrgUnitRow scopeUnit = access.requireScopeOrgUnit(scope, tenant.id());
            rows = rows.stream().filter(row -> access.isWithinSubtree(scopeUnit, row.path())).toList();
        }
        List<OrgUnitView> flat = project(tenant.id(), rows);
        return switch (resolvedFormat) {
            case "tree" -> toTree(flat);
            case "flat" -> flat;
            default -> throw BizException.invalid("format must be 'tree' or 'flat'");
        };
    }

    public OrgUnitView get(String tenantId, String id) {
        TenantScope scope = access.requireScope();
        TenantRow tenant = scopedTenant(scope, tenantId);
        OrgUnitRow row = load(tenant.id(), id);
        access.assertOrgReadable(scope, tenant.id(), row);
        return view(tenant.id(), row);
    }

    // ------------------------------------------------------------------ commands

    public OrgUnitView create(String tenantId, OrgUnitCreateRequest request) {
        TenantScope scope = access.requireScope();
        TenantRow tenant = scopedTenant(scope, tenantId);
        access.requireOrgWrite(scope);
        if (request == null) {
            throw BizException.invalid("request body is required");
        }
        // docs/17 section 4: organization unit quota is enforced over non deleted units.
        quotaGuard.assertOrgUnitsAvailable(tenant, orgUnits.countByTenant(tenant.id()));
        OrgUnitType type = OrgUnitType.parse(request.type());
        String code = request.code() == null ? null : request.code().trim();
        if (code == null || code.isBlank()) {
            throw BizException.invalid("org unit code is required");
        }
        if (orgUnits.findByCode(tenant.id(), code).isPresent()) {
            throw BizException.conflict("org unit code already exists in tenant: " + code);
        }
        OrgUnitRow parent = null;
        if (Texts.blankToNull(request.parentId()) != null) {
            parent = load(tenant.id(), request.parentId().trim());
        }
        if (access.isOrgAdmin(scope)) {
            if (parent == null) {
                throw BizException.forbidden("ORG_ADMIN cannot create a root organization unit");
            }
            access.assertOrgWritable(scope, tenant.id(), parent);
        }
        int depth = parent == null ? 0 : parent.depth() + 1;
        if (depth > MAX_DEPTH) {
            throw BizException.invalid("org unit depth must not exceed " + MAX_DEPTH);
        }
        String managerAccountId = validateManager(tenant.id(), request.managerAccountId());
        if (access.isOrgAdmin(scope) && managerAccountId != null) {
            assertManagerInScope(scope, tenant.id(), managerAccountId);
        }
        String id = UUID.randomUUID().toString();
        var now = TimeUtil.now();
        OrgUnitRow row = new OrgUnitRow(
                id,
                tenant.id(),
                parent == null ? null : parent.id(),
                code,
                request.name().trim(),
                type.name(),
                parent == null ? "/" + id : parent.path() + "/" + id,
                depth,
                request.sortOrder() == null ? 0 : request.sortOrder(),
                managerAccountId,
                OrgUnitStatus.ACTIVE.name(),
                now,
                now,
                0L);
        try {
            orgUnits.insert(row);
        } catch (DuplicateKeyException ex) {
            throw BizException.conflict("org unit code already exists in tenant: " + code);
        }
        auditService.record(tenant.id(), access.actor(scope), "ORG_CREATED",
                "org unit " + code + " created under " + (parent == null ? "root" : parent.code()));
        return view(tenant.id(), row);
    }

    public OrgUnitView update(String tenantId, String id, OrgUnitUpdateRequest request) {
        TenantScope scope = access.requireScope();
        TenantRow tenant = scopedTenant(scope, tenantId);
        access.requireOrgWrite(scope);
        if (request == null) {
            throw BizException.invalid("request body is required");
        }
        OrgUnitRow row = load(tenant.id(), id);
        access.assertOrgWritable(scope, tenant.id(), row);
        OrgUnitType type = OrgUnitType.parse(request.type());
        String managerAccountId = validateManager(tenant.id(), request.managerAccountId());
        if (access.isOrgAdmin(scope) && managerAccountId != null) {
            assertManagerInScope(scope, tenant.id(), managerAccountId);
        }
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("name", request.name().trim());
        fields.put("type", type.name());
        fields.put("sort_order", request.sortOrder() == null ? row.sortOrder() : request.sortOrder());
        fields.put("manager_account_id", managerAccountId);
        fields.put("updated_at", TimeUtil.now());
        if (orgUnits.updateFields(tenant.id(), row.id(), row.version(), fields) == 0) {
            throw BizException.conflict("org unit " + row.code() + " was modified concurrently, please retry");
        }
        auditService.record(tenant.id(), access.actor(scope), "ORG_UPDATED",
                "org unit " + row.code() + " updated");
        return view(tenant.id(), load(tenant.id(), id));
    }

    @Transactional
    public OrgUnitView move(String tenantId, String id, OrgUnitMoveRequest request) {
        TenantScope scope = access.requireScope();
        TenantRow tenant = scopedTenant(scope, tenantId);
        access.requireOrgWrite(scope);
        OrgUnitRow row = load(tenant.id(), id);
        access.assertOrgWritable(scope, tenant.id(), row);
        String parentId = request == null ? null : Texts.blankToNull(request.parentId());

        OrgUnitRow newParent = null;
        if (parentId != null) {
            newParent = load(tenant.id(), parentId);
            if (newParent.id().equals(row.id()) || newParent.path().equals(row.path())
                    || newParent.path().startsWith(row.path() + "/")) {
                throw BizException.invalid("cannot move an org unit into itself or its own subtree");
            }
        }
        if (access.isOrgAdmin(scope)) {
            if (newParent == null) {
                throw BizException.forbidden("ORG_ADMIN cannot move an org unit outside of its subtree");
            }
            access.assertOrgWritable(scope, tenant.id(), newParent);
        }
        int newDepth = newParent == null ? 0 : newParent.depth() + 1;
        List<OrgUnitRow> subtree = orgUnits.findSubtree(tenant.id(), row.path());
        int maxDepth = subtree.stream().mapToInt(OrgUnitRow::depth).max().orElse(row.depth());
        int shift = newDepth - row.depth();
        if (maxDepth + shift > MAX_DEPTH) {
            throw BizException.invalid("move would exceed the maximum org unit depth of " + MAX_DEPTH);
        }
        String oldPath = row.path();
        String newPath = newParent == null ? "/" + row.id() : newParent.path() + "/" + row.id();
        var now = TimeUtil.now();

        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("parent_id", newParent == null ? null : newParent.id());
        fields.put("path", newPath);
        fields.put("depth", newDepth);
        fields.put("updated_at", now);
        if (orgUnits.updateFields(tenant.id(), row.id(), row.version(), fields) == 0) {
            throw BizException.conflict("org unit " + row.code() + " was modified concurrently, please retry");
        }
        for (OrgUnitRow descendant : subtree) {
            if (descendant.id().equals(row.id())) {
                continue;
            }
            String descendantPath = newPath + descendant.path().substring(oldPath.length());
            orgUnits.updateSubtreeRow(tenant.id(), descendant.id(), descendantPath,
                    descendant.depth() + shift, now);
        }
        auditService.record(tenant.id(), access.actor(scope), "ORG_MOVED",
                "org unit " + row.code() + " moved from " + oldPath + " to " + newPath);
        return view(tenant.id(), load(tenant.id(), id));
    }

    public void delete(String tenantId, String id) {
        TenantScope scope = access.requireScope();
        TenantRow tenant = scopedTenant(scope, tenantId);
        access.requireOrgWrite(scope);
        OrgUnitRow row = load(tenant.id(), id);
        access.assertOrgWritable(scope, tenant.id(), row);
        long children = orgUnits.countChildren(tenant.id(), row.id());
        long members = accounts.countByOrgUnitSubtree(tenant.id(), row.path());
        if (children > 0 || members > 0) {
            throw BizException.conflict("org unit has " + children + " child unit(s) and "
                    + members + " account(s); move or delete them first");
        }
        orgUnits.deleteById(tenant.id(), row.id());
        auditService.record(tenant.id(), access.actor(scope), "ORG_DELETED",
                "org unit " + row.code() + " deleted");
    }

    // ------------------------------------------------------------------ projection

    private List<OrgUnitView> project(String tenantId, List<OrgUnitRow> rows) {
        Map<String, Long> directCounts = accounts.countGroupedByOrgUnit(tenantId);
        Map<String, String> managerIds = new LinkedHashMap<>();
        for (OrgUnitRow row : rows) {
            if (row.managerAccountId() != null) {
                managerIds.put(row.id(), row.managerAccountId());
            }
        }
        Map<String, String> managerNames = new HashMap<>();
        if (!managerIds.isEmpty()) {
            managerNames.putAll(accounts.displayNames(tenantId, managerIds.values()));
        }
        Map<String, Integer> childCounts = new HashMap<>();
        for (OrgUnitRow row : rows) {
            if (row.parentId() != null) {
                childCounts.merge(row.parentId(), 1, Integer::sum);
            }
        }
        List<OrgUnitView> views = new ArrayList<>(rows.size());
        for (OrgUnitRow row : rows) {
            long accountCount = 0L;
            for (OrgUnitRow candidate : rows) {
                if (candidate.path().equals(row.path()) || candidate.path().startsWith(row.path() + "/")) {
                    accountCount += directCounts.getOrDefault(candidate.id(), 0L);
                }
            }
            String managerName = row.managerAccountId() == null
                    ? null
                    : managerNames.get(row.managerAccountId());
            views.add(OrgUnitView.of(row,
                    managerName,
                    accountCount,
                    childCounts.getOrDefault(row.id(), 0)));
        }
        return views;
    }

    private List<OrgUnitView> toTree(List<OrgUnitView> flat) {
        Map<String, OrgUnitView> byId = new LinkedHashMap<>();
        for (OrgUnitView view : flat) {
            byId.put(view.id(), view);
        }
        Map<String, List<OrgUnitView>> children = new LinkedHashMap<>();
        List<OrgUnitView> roots = new ArrayList<>();
        for (OrgUnitView view : flat) {
            if (view.parentId() != null && byId.containsKey(view.parentId())) {
                children.computeIfAbsent(view.parentId(), key -> new ArrayList<>()).add(view);
            } else {
                roots.add(view);
            }
        }
        List<OrgUnitView> result = new ArrayList<>(roots.size());
        for (OrgUnitView root : roots) {
            result.add(attach(root, children));
        }
        result.sort(Comparator.comparingInt(OrgUnitView::sortOrder).thenComparing(OrgUnitView::code));
        return result;
    }

    private OrgUnitView attach(OrgUnitView view, Map<String, List<OrgUnitView>> children) {
        List<OrgUnitView> nested = children.get(view.id());
        if (nested == null || nested.isEmpty()) {
            return view.withChildren(List.of());
        }
        List<OrgUnitView> attached = new ArrayList<>(nested.size());
        for (OrgUnitView child : nested) {
            attached.add(attach(child, children));
        }
        attached.sort(Comparator.comparingInt(OrgUnitView::sortOrder).thenComparing(OrgUnitView::code));
        return view.withChildren(attached);
    }

    private OrgUnitView view(String tenantId, OrgUnitRow row) {
        String managerName = null;
        if (row.managerAccountId() != null) {
            managerName = accounts.displayNames(tenantId, List.of(row.managerAccountId()))
                    .get(row.managerAccountId());
        }
        return OrgUnitView.of(row,
                managerName,
                accounts.countByOrgUnitSubtree(tenantId, row.path()),
                orgUnits.countChildren(tenantId, row.id()));
    }

    // ------------------------------------------------------------------ helpers

    private TenantRow scopedTenant(TenantScope scope, String tenantId) {
        TenantRow tenant = access.requireTenant(tenantId);
        access.assertSameTenant(scope, tenant);
        return tenant;
    }

    private OrgUnitRow load(String tenantId, String id) {
        return orgUnits.findById(tenantId, id)
                .orElseThrow(() -> BizException.notFound("org unit not found: " + id));
    }

    private String validateManager(String tenantId, String managerAccountId) {
        String manager = Texts.blankToNull(managerAccountId);
        if (manager == null) {
            return null;
        }
        accounts.findById(tenantId, manager)
                .orElseThrow(() -> BizException.invalid("managerAccountId does not exist in this tenant"));
        return manager;
    }

    /** ORG_ADMIN may only appoint managers whose account lives inside its own subtree. */
    private void assertManagerInScope(TenantScope scope, String tenantId, String managerAccountId) {
        var account = accounts.findById(tenantId, managerAccountId)
                .orElseThrow(() -> BizException.invalid("managerAccountId does not exist in this tenant"));
        access.assertAccountWritable(scope, tenantId, account);
    }
}
