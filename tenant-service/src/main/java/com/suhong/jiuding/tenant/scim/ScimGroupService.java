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
package com.suhong.jiuding.tenant.scim;

import com.suhong.jiuding.tenant.domain.OrgUnitStatus;
import com.suhong.jiuding.tenant.domain.OrgUnitType;
import com.suhong.jiuding.tenant.repository.AccountRepository;
import com.suhong.jiuding.tenant.repository.AccountRow;
import com.suhong.jiuding.tenant.repository.OrgUnitRepository;
import com.suhong.jiuding.tenant.repository.OrgUnitRow;
import com.suhong.jiuding.tenant.service.AuditService;
import com.suhong.jiuding.tenant.support.Texts;
import com.suhong.jiuding.tenant.support.TimeUtil;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SCIM 2.0 Groups resource mapped onto {@code org_units} (docs/16 section 7.1).
 *
 * <p>displayName -&gt; org unit name, externalId -&gt; code, the
 * {@code urn:tenancy:params:2.0:Group} extension carries parentId/type/path/managerId and
 * members are account ids (moving accounts between organization units).
 */
@Service
public class ScimGroupService {

    private static final Pattern MEMBER_ID_PATTERN = Pattern.compile("value\\s+eq\\s+[\"']?([^\\s\\]\"']+)");

    private final OrgUnitRepository orgUnits;
    private final AccountRepository accounts;
    private final ScimDirectoryRepository directory;
    private final AuditService auditService;

    public ScimGroupService(OrgUnitRepository orgUnits,
                            AccountRepository accounts,
                            ScimDirectoryRepository directory,
                            AuditService auditService) {
        this.orgUnits = orgUnits;
        this.accounts = accounts;
        this.directory = directory;
        this.auditService = auditService;
    }

    // ------------------------------------------------------------------ queries

    public Map<String, Object> list(String tenantId, String filter, Integer startIndex, Integer count) {
        ScimSupport.Filter parsed = ScimSupport.parseFilter(filter);
        if (parsed != null && !"displayName".equals(parsed.attribute())) {
            throw ScimException.badRequest("unsupported filter attribute: " + parsed.attribute());
        }
        ScimSupport.Page page = ScimSupport.page(startIndex, count);
        List<OrgUnitRow> all = orgUnits.list(tenantId, null, null);
        List<OrgUnitRow> filtered = new ArrayList<>();
        for (OrgUnitRow row : all) {
            if (parsed == null || row.name().equalsIgnoreCase(parsed.value())) {
                filtered.add(row);
            }
        }
        long total = filtered.size();
        long from = Math.min(page.offset(), total);
        long to = Math.min(from + page.count(), total);
        List<Map<String, Object>> resources = new ArrayList<>();
        for (OrgUnitRow row : filtered.subList((int) from, (int) to)) {
            resources.add(resource(tenantId, row));
        }
        return ScimSupport.listResponse(total, page.startIndex(), resources);
    }

    public Map<String, Object> get(String tenantId, String id) {
        return resource(tenantId, requireGroup(tenantId, id));
    }

    // ------------------------------------------------------------------ commands

    public Map<String, Object> create(String tenantId, Map<String, Object> body, String actor) {
        String displayName = ScimSupport.str(body, "displayName");
        if (displayName == null) {
            throw ScimException.badRequest("displayName is required");
        }
        String code = resolveCreateCode(tenantId, displayName, ScimSupport.str(body, "externalId"));
        OrgUnitRow parent = resolveParent(tenantId, body, null);
        OrgUnitType type = resolveType(body);
        int depth = parent == null ? 0 : parent.depth() + 1;
        if (depth > 6) {
            throw ScimException.badRequest("org unit depth must not exceed 6");
        }
        String managerId = resolveManager(tenantId, body);
        String id = UUID.randomUUID().toString();
        var now = TimeUtil.now();
        OrgUnitRow row = new OrgUnitRow(
                id,
                tenantId,
                parent == null ? null : parent.id(),
                code,
                displayName,
                type.name(),
                parent == null ? "/" + id : parent.path() + "/" + id,
                depth,
                resolveSortOrder(body),
                managerId,
                OrgUnitStatus.ACTIVE.name(),
                now,
                now,
                0L);
        try {
            orgUnits.insert(row);
        } catch (DuplicateKeyException ex) {
            throw ScimException.conflict("group code already exists: " + code);
        }
        List<String> members = ScimSupport.values(body, "members");
        if (!members.isEmpty()) {
            applyMembers(tenantId, id, members, MemberMode.REPLACE);
        }
        auditService.record(tenantId, actor, "SCIM_GROUP_CREATED",
                "SCIM group " + code + " created (" + id + ")");
        return resource(tenantId, requireGroup(tenantId, id));
    }

    public Map<String, Object> replace(String tenantId, String id, Map<String, Object> body, String actor) {
        OrgUnitRow existing = requireGroup(tenantId, id);
        String externalId = ScimSupport.str(body, "externalId");
        if (externalId != null && !externalId.equals(existing.code())) {
            throw ScimException.badRequest("externalId/code is immutable");
        }
        String displayName = ScimSupport.str(body, "displayName");
        if (displayName == null) {
            displayName = existing.name();
        }
        OrgUnitRow parent = resolveParent(tenantId, body, existing);
        if (parent != null && !parent.id().equals(nullSafe(existing.parentId()))) {
            throw ScimException.badRequest("parentId cannot be changed via SCIM");
        }
        OrgUnitType type = resolveType(body);
        String managerId = resolveManager(tenantId, body);
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("name", displayName);
        fields.put("type", type.name());
        fields.put("sort_order", resolveSortOrder(body));
        fields.put("manager_account_id", managerId);
        fields.put("updated_at", TimeUtil.now());
        if (orgUnits.updateFields(tenantId, existing.id(), existing.version(), fields) == 0) {
            throw ScimException.conflict("group was modified concurrently, please retry");
        }
        if (body != null && body.containsKey("members")) {
            applyMembers(tenantId, id, ScimSupport.values(body, "members"), MemberMode.REPLACE);
        }
        auditService.record(tenantId, actor, "SCIM_GROUP_UPDATED",
                "SCIM group " + existing.code() + " updated (" + id + ")");
        return resource(tenantId, requireGroup(tenantId, id));
    }

    public Map<String, Object> patch(String tenantId, String id, Map<String, Object> body, String actor) {
        OrgUnitRow existing = requireGroup(tenantId, id);
        String displayName = existing.name();
        OrgUnitType type = OrgUnitType.parse(existing.type());
        String managerId = existing.managerAccountId();
        List<Map<String, Object>> operations = ScimSupport.asMapList(
                body == null ? null : body.getOrDefault("Operations", body.get("operations")));

        List<String> addMembers = new ArrayList<>();
        List<String> removeMembers = new ArrayList<>();
        List<String> replaceMembers = null;
        for (Map<String, Object> operation : operations) {
            String op = String.valueOf(operation.getOrDefault("op", "replace")).toLowerCase(Locale.ROOT);
            String path = operation.get("path") == null
                    ? "" : String.valueOf(operation.get("path")).trim();
            Object value = operation.get("value");
            String normalizedPath = path.toLowerCase(Locale.ROOT);
            if (normalizedPath.isEmpty()) {
                if (value instanceof Map<?, ?> map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> valueMap = (Map<String, Object>) map;
                    displayName = orDefault(ScimSupport.str(valueMap, "displayName"), displayName);
                    if (valueMap.get("members") != null) {
                        replaceMembers = ScimSupport.memberIds(valueMap.get("members"));
                    }
                }
            } else if (normalizedPath.equals("displayname")) {
                displayName = orDefault(ScimSupport.valueText(value), displayName);
            } else if (normalizedPath.startsWith("members")) {
                List<String> ids = new ArrayList<>();
                if (normalizedPath.contains("eq")) {
                    Matcher matcher = MEMBER_ID_PATTERN.matcher(path);
                    if (matcher.find()) {
                        ids.add(matcher.group(1));
                    }
                } else {
                    ids.addAll(ScimSupport.memberIds(value));
                }
                if ("add".equals(op)) {
                    addMembers.addAll(ids);
                } else if ("remove".equals(op)) {
                    removeMembers.addAll(ids);
                } else {
                    replaceMembers = ids;
                }
            } else if (normalizedPath.equals("externalid")) {
                throw ScimException.badRequest("externalId/code is immutable");
            } else if (normalizedPath.contains("type")) {
                Object typeValue = value instanceof Map<?, ?> map ? map.get("type") : value;
                String typeText = ScimSupport.valueText(typeValue);
                if (typeText != null) {
                    type = parseType(typeText);
                }
            } else if (normalizedPath.contains("managerid")) {
                String candidate = ScimSupport.valueText(value);
                if (candidate != null) {
                    managerId = validateManager(tenantId, candidate);
                }
            }
        }

        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("name", displayName);
        fields.put("type", type.name());
        fields.put("manager_account_id", managerId);
        fields.put("updated_at", TimeUtil.now());
        if (orgUnits.updateFields(tenantId, existing.id(), existing.version(), fields) == 0) {
            throw ScimException.conflict("group was modified concurrently, please retry");
        }
        if (replaceMembers != null) {
            applyMembers(tenantId, id, replaceMembers, MemberMode.REPLACE);
        }
        if (!addMembers.isEmpty()) {
            applyMembers(tenantId, id, addMembers, MemberMode.ADD);
        }
        if (!removeMembers.isEmpty()) {
            applyMembers(tenantId, id, removeMembers, MemberMode.REMOVE);
        }
        auditService.record(tenantId, actor, "SCIM_GROUP_UPDATED",
                "SCIM group " + existing.code() + " updated (" + id + ")");
        return resource(tenantId, requireGroup(tenantId, id));
    }

    public void delete(String tenantId, String id, String actor) {
        OrgUnitRow existing = requireGroup(tenantId, id);
        long children = orgUnits.countChildren(tenantId, id);
        long members = directory.listMembers(tenantId, id).size();
        if (children > 0 || members > 0) {
            throw ScimException.conflict("group has " + children + " child group(s) and "
                    + members + " member(s); remove them first");
        }
        orgUnits.deleteById(tenantId, id);
        auditService.record(tenantId, actor, "SCIM_GROUP_DELETED",
                "SCIM group " + existing.code() + " deleted (" + id + ")");
    }

    // ------------------------------------------------------------------ members

    private enum MemberMode {
        REPLACE, ADD, REMOVE
    }

    private void applyMembers(String tenantId, String groupId, List<String> requestedIds, MemberMode mode) {
        Set<String> target = new LinkedHashSet<>();
        for (String id : requestedIds) {
            directory.findAccount(tenantId, id)
                    .orElseThrow(() -> ScimException.badRequest("member not found: " + id));
            target.add(id);
        }
        Set<String> current = new LinkedHashSet<>();
        for (ScimDirectoryRepository.ScimAccountRow member : directory.listMembers(tenantId, groupId)) {
            current.add(member.id());
        }
        Set<String> toAdd = new LinkedHashSet<>();
        Set<String> toRemove = new LinkedHashSet<>();
        switch (mode) {
            case REPLACE -> {
                for (String id : target) {
                    if (!current.contains(id)) {
                        toAdd.add(id);
                    }
                }
                for (String id : current) {
                    if (!target.contains(id)) {
                        toRemove.add(id);
                    }
                }
            }
            case ADD -> {
                for (String id : target) {
                    if (!current.contains(id)) {
                        toAdd.add(id);
                    }
                }
            }
            case REMOVE -> {
                for (String id : target) {
                    if (current.contains(id)) {
                        toRemove.add(id);
                    }
                }
            }
        }
        for (String id : toRemove) {
            moveAccount(tenantId, id, null);
        }
        for (String id : toAdd) {
            moveAccount(tenantId, id, groupId);
        }
    }

    private void moveAccount(String tenantId, String accountId, String orgUnitId) {
        AccountRow account = accounts.findById(tenantId, accountId)
                .orElseThrow(() -> ScimException.badRequest("member not found: " + accountId));
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("org_unit_id", orgUnitId);
        fields.put("updated_at", TimeUtil.now());
        if (accounts.updateFields(tenantId, accountId, account.version(), fields) == 0) {
            throw ScimException.conflict("member account was modified concurrently, please retry");
        }
    }

    // ------------------------------------------------------------------ mapping / helpers

    private Map<String, Object> resource(String tenantId, OrgUnitRow row) {
        Map<String, Object> resource = new LinkedHashMap<>();
        resource.put("schemas", List.of(ScimSupport.GROUP_SCHEMA, ScimSupport.GROUP_EXTENSION));
        resource.put("id", row.id());
        resource.put("displayName", row.name());
        resource.put("externalId", row.code());
        List<ScimDirectoryRepository.ScimAccountRow> members = directory.listMembers(tenantId, row.id());
        List<Map<String, Object>> memberResources = new ArrayList<>();
        for (ScimDirectoryRepository.ScimAccountRow member : members) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("value", member.id());
            item.put("display", member.displayName());
            item.put("$ref", "/scim/v2/Users/" + member.id());
            memberResources.add(item);
        }
        resource.put("members", memberResources);
        Map<String, Object> extension = new LinkedHashMap<>();
        extension.put("code", row.code());
        extension.put("parentId", row.parentId());
        extension.put("type", row.type());
        extension.put("path", row.path());
        extension.put("sortOrder", row.sortOrder());
        extension.put("status", row.status());
        if (row.managerAccountId() != null) {
            extension.put("managerId", row.managerAccountId());
            Map<String, String> names = accounts.displayNames(tenantId, List.of(row.managerAccountId()));
            resource.put("managers", List.of(Map.of("value", row.managerAccountId(),
                    "display", names.getOrDefault(row.managerAccountId(), ""))));
        }
        resource.put(ScimSupport.GROUP_EXTENSION, extension);
        resource.put("meta", ScimSupport.meta("Group", row.createdAt(), row.updatedAt(),
                "/scim/v2/Groups/" + row.id()));
        return resource;
    }

    private OrgUnitRow requireGroup(String tenantId, String id) {
        return orgUnits.findById(tenantId, id)
                .orElseThrow(() -> ScimException.notFound("Group not found: " + id));
    }

    private String resolveCreateCode(String tenantId, String displayName, String externalId) {
        if (externalId != null) {
            if (!externalId.matches("^[A-Za-z0-9][A-Za-z0-9_-]{0,63}$")) {
                throw ScimException.badRequest("invalid externalId: " + externalId);
            }
            if (orgUnits.findByCode(tenantId, externalId).isPresent()) {
                throw ScimException.conflict("group code already exists: " + externalId);
            }
            return externalId;
        }
        String base = displayName.toUpperCase(Locale.ROOT)
                .replaceAll("[^A-Z0-9]+", "_")
                .replaceAll("^_+|_+$", "");
        if (base.isBlank()) {
            base = "GROUP";
        }
        if (!Character.isLetter(base.charAt(0))) {
            base = "G" + base;
        }
        if (base.length() > 60) {
            base = base.substring(0, 60);
        }
        String candidate = base;
        int attempt = 0;
        while (orgUnits.findByCode(tenantId, candidate).isPresent()) {
            candidate = base + "_" + Integer.toHexString((int) (Math.random() * 0xFFFF)).toUpperCase(Locale.ROOT);
            if (++attempt > 10) {
                throw ScimException.conflict("could not generate a unique group code for " + displayName);
            }
        }
        return candidate;
    }

    private OrgUnitRow resolveParent(String tenantId, Map<String, Object> body, OrgUnitRow existing) {
        String parentId = ScimSupport.extensionString(body, ScimSupport.GROUP_EXTENSION, "parentId");
        if (parentId == null) {
            parentId = ScimSupport.str(body, "parentId");
        }
        if (parentId == null) {
            return existing == null || existing.parentId() == null
                    ? null : orgUnits.findById(tenantId, existing.parentId()).orElse(null);
        }
        final String resolvedParentId = parentId;
        return orgUnits.findById(tenantId, resolvedParentId)
                .orElseThrow(() -> ScimException.badRequest("parentId does not exist: " + resolvedParentId));
    }

    private OrgUnitType resolveType(Map<String, Object> body) {
        String type = ScimSupport.extensionString(body, ScimSupport.GROUP_EXTENSION, "type");
        if (type == null) {
            type = ScimSupport.str(body, "type");
        }
        return type == null ? OrgUnitType.DEPARTMENT : parseType(type);
    }

    private OrgUnitType parseType(String raw) {
        try {
            return OrgUnitType.parse(raw);
        } catch (RuntimeException ex) {
            throw ScimException.badRequest("unsupported group type: " + raw);
        }
    }

    private String resolveManager(String tenantId, Map<String, Object> body) {
        String managerId = ScimSupport.extensionString(body, ScimSupport.GROUP_EXTENSION, "managerId");
        if (managerId == null) {
            managerId = ScimSupport.firstValue(body, "managers");
        }
        return managerId == null ? null : validateManager(tenantId, managerId);
    }

    private String validateManager(String tenantId, String accountId) {
        directory.findAccount(tenantId, accountId)
                .orElseThrow(() -> ScimException.badRequest("manager not found: " + accountId));
        return accountId;
    }

    private int resolveSortOrder(Map<String, Object> body) {
        Object raw = ScimSupport.extension(body, ScimSupport.GROUP_EXTENSION) == null
                ? null : ScimSupport.extension(body, ScimSupport.GROUP_EXTENSION).get("sortOrder");
        if (raw instanceof Number number) {
            return number.intValue();
        }
        if (raw instanceof String text) {
            try {
                return Integer.parseInt(text.trim());
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
        return 0;
    }

    private static String orDefault(String value, String fallback) {
        return value == null ? fallback : value;
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }
}
