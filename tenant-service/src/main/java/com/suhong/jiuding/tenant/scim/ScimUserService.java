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

import com.suhong.jiuding.tenant.domain.AccountRole;
import com.suhong.jiuding.tenant.domain.AccountStatus;
import com.suhong.jiuding.tenant.repository.AccountRepository;
import com.suhong.jiuding.tenant.repository.AccountRow;
import com.suhong.jiuding.tenant.repository.OrgUnitRepository;
import com.suhong.jiuding.tenant.service.AuditService;
import com.suhong.jiuding.tenant.service.PasswordHasher;
import com.suhong.jiuding.tenant.support.Texts;
import com.suhong.jiuding.tenant.support.TimeUtil;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * SCIM 2.0 Users resource mapped onto {@code accounts} (docs/16 section 7.1).
 *
 * <p>userName -&gt; username (lower case, unique per tenant), displayName -&gt; display_name,
 * emails[0].value -&gt; email, phoneNumbers[0].value -&gt; phone, active -&gt; status
 * (ACTIVE/DISABLED), roles[0].value -&gt; account role and the
 * {@code urn:tenancy:params:2.0:User} extension carries orgUnitId.
 */
@Service
public class ScimUserService {

    private final ScimDirectoryRepository directory;
    private final AccountRepository accounts;
    private final OrgUnitRepository orgUnits;
    private final PasswordHasher passwordHasher;
    private final AuditService auditService;

    public ScimUserService(ScimDirectoryRepository directory,
                           AccountRepository accounts,
                           OrgUnitRepository orgUnits,
                           PasswordHasher passwordHasher,
                           AuditService auditService) {
        this.directory = directory;
        this.accounts = accounts;
        this.orgUnits = orgUnits;
        this.passwordHasher = passwordHasher;
        this.auditService = auditService;
    }

    // ------------------------------------------------------------------ queries

    public Map<String, Object> list(String tenantId, String filter, Integer startIndex, Integer count) {
        ScimSupport.Filter parsed = ScimSupport.parseFilter(filter);
        ScimSupport.Page page = ScimSupport.page(startIndex, count);
        String attribute = parsed == null ? null : parsed.attribute();
        String value = parsed == null ? null : parsed.value();
        long total = directory.countAccounts(tenantId, attribute, value);
        List<ScimDirectoryRepository.ScimAccountRow> rows = directory.listAccounts(
                tenantId, attribute, value, page.offset(), page.count());
        List<Map<String, Object>> resources = new ArrayList<>(rows.size());
        for (ScimDirectoryRepository.ScimAccountRow row : rows) {
            resources.add(resource(tenantId, row));
        }
        return ScimSupport.listResponse(total, page.startIndex(), resources);
    }

    public Map<String, Object> get(String tenantId, String id) {
        return resource(tenantId, requireUser(tenantId, id));
    }

    // ------------------------------------------------------------------ commands

    public Map<String, Object> create(String tenantId, Map<String, Object> body, String actor) {
        String username = ScimSupport.str(body, "userName");
        if (username == null) {
            throw ScimException.badRequest("userName is required");
        }
        username = passwordHasher.normalizeUsername(username);
        validateUsername(username);
        if (directory.findAnyAccountByUsername(tenantId, username).isPresent()) {
            throw ScimException.conflict("userName already exists: " + username);
        }
        String orgUnitId = resolveOrgUnit(tenantId, body, null);
        AccountRole role = resolveRole(body, AccountRole.MEMBER);
        boolean active = ScimSupport.bool(body, "active", true);
        String displayName = displayName(body, username);
        String email = ScimSupport.firstValue(body, "emails");
        String phone = ScimSupport.firstValue(body, "phoneNumbers");

        String temporary = passwordHasher.generateTemporary();
        PasswordHasher.Credential credential = passwordHasher.hash(temporary);
        var now = TimeUtil.now();
        AccountRow row = new AccountRow(
                UUID.randomUUID().toString(),
                tenantId,
                orgUnitId,
                username,
                displayName,
                Texts.blankToNull(email),
                Texts.blankToNull(phone),
                role.name(),
                active ? AccountStatus.ACTIVE.name() : AccountStatus.DISABLED.name(),
                credential.passwordHash(),
                credential.passwordSalt(),
                true,
                0,
                null,
                actor,
                now,
                now,
                null,
                0L);
        try {
            accounts.insert(row);
        } catch (DuplicateKeyException ex) {
            throw ScimException.conflict("userName already exists: " + username);
        }
        auditService.record(tenantId, actor, "SCIM_USER_CREATED",
                "SCIM user " + username + " created (" + row.id() + ")");
        return resource(tenantId, requireUser(tenantId, row.id()));
    }

    public Map<String, Object> replace(String tenantId, String id, Map<String, Object> body, String actor) {
        ScimDirectoryRepository.ScimAccountRow existing = requireUser(tenantId, id);
        String username = ScimSupport.str(body, "userName");
        if (username != null && !username.equalsIgnoreCase(existing.username())) {
            throw ScimException.badRequest("userName is immutable");
        }
        String displayName = displayName(body, existing.displayName());
        String email = ScimSupport.firstValue(body, "emails");
        String phone = ScimSupport.firstValue(body, "phoneNumbers");
        AccountRole role = resolveRole(body, AccountRole.parse(existing.role()));
        boolean active = ScimSupport.bool(body, "active", AccountStatus.ACTIVE.name().equals(existing.status()));
        String orgUnitId = resolveOrgUnit(tenantId, body, existing.orgUnitId());

        Map<String, Object> fields = mutableFields(displayName, email, phone, role, active, orgUnitId);
        return update(tenantId, existing, fields, actor, "SCIM_USER_UPDATED");
    }

    public Map<String, Object> patch(String tenantId, String id, Map<String, Object> body, String actor) {
        ScimDirectoryRepository.ScimAccountRow existing = requireUser(tenantId, id);
        String displayName = existing.displayName();
        String email = existing.email();
        String phone = existing.phone();
        AccountRole role = AccountRole.parse(existing.role());
        boolean active = AccountStatus.ACTIVE.name().equals(existing.status());
        String orgUnitId = existing.orgUnitId();

        List<Map<String, Object>> operations = ScimSupport.asMapList(
                body == null ? null : body.getOrDefault("Operations", body.get("operations")));
        if (operations.isEmpty() && body != null && body.get("value") instanceof Map<?, ?> map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> value = (Map<String, Object>) map;
            displayName = valueOrDefault(ScimSupport.str(value, "displayName"), displayName);
            active = value.containsKey("active") ? ScimSupport.bool(value, "active", active) : active;
            email = valueOrDefault(ScimSupport.firstValue(value, "emails"), email);
            phone = valueOrDefault(ScimSupport.firstValue(value, "phoneNumbers"), phone);
            if (value.get("roles") != null) {
                role = resolveRole(value, role);
            }
            orgUnitId = valueOrDefault(resolveOrgUnit(tenantId, value, orgUnitId), orgUnitId);
        }
        for (Map<String, Object> operation : operations) {
            String path = operation.get("path") == null ? "" : String.valueOf(operation.get("path")).trim();
            Object value = operation.get("value");
            String normalizedPath = path.toLowerCase(java.util.Locale.ROOT);
            if (normalizedPath.isEmpty()) {
                if (value instanceof Map<?, ?> map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> valueMap = (Map<String, Object>) map;
                    displayName = valueOrDefault(ScimSupport.str(valueMap, "displayName"), displayName);
                    active = valueMap.containsKey("active")
                            ? ScimSupport.bool(valueMap, "active", active) : active;
                    email = valueOrDefault(ScimSupport.firstValue(valueMap, "emails"), email);
                    phone = valueOrDefault(ScimSupport.firstValue(valueMap, "phoneNumbers"), phone);
                    if (valueMap.get("roles") != null) {
                        role = resolveRole(valueMap, role);
                    }
                    orgUnitId = valueOrDefault(resolveOrgUnit(tenantId, valueMap, orgUnitId), orgUnitId);
                }
            } else if (normalizedPath.equals("displayname") || normalizedPath.equals("name.formatted")) {
                displayName = valueOrDefault(ScimSupport.valueText(value), displayName);
            } else if (normalizedPath.equals("active")) {
                String text = ScimSupport.valueText(value);
                active = Boolean.parseBoolean(text);
            } else if (normalizedPath.startsWith("emails")) {
                email = valueOrDefault(extractArrayValue(value), email);
            } else if (normalizedPath.startsWith("phonenumbers")) {
                phone = valueOrDefault(extractArrayValue(value), phone);
            } else if (normalizedPath.startsWith("roles")) {
                String roleValue = ScimSupport.valueText(value);
                if (roleValue != null) {
                    role = parseRole(roleValue);
                }
            } else if (normalizedPath.equals("username")) {
                throw ScimException.badRequest("userName is immutable");
            } else if (normalizedPath.contains("orgunit")) {
                String candidate = ScimSupport.valueText(
                        value instanceof Map<?, ?> map ? ((Map<?, ?>) map).get("orgUnitId") : value);
                if (candidate != null) {
                    orgUnitId = validateOrgUnit(tenantId, candidate);
                }
            }
        }
        Map<String, Object> fields = mutableFields(displayName, email, phone, role, active, orgUnitId);
        return update(tenantId, existing, fields, actor, "SCIM_USER_UPDATED");
    }

    public void delete(String tenantId, String id, String actor) {
        ScimDirectoryRepository.ScimAccountRow existing = requireUser(tenantId, id);
        var now = TimeUtil.now();
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("deleted_at", now);
        fields.put("status", AccountStatus.DELETED.name());
        fields.put("updated_at", now);
        if (accounts.updateFields(tenantId, existing.id(), existing.version(), fields) == 0) {
            throw ScimException.conflict("account was modified concurrently, please retry");
        }
        orgUnits.clearManagerReference(tenantId, existing.id(), now);
        auditService.record(tenantId, actor, "SCIM_USER_DELETED",
                "SCIM user " + existing.username() + " soft deleted (" + existing.id() + ")");
    }

    // ------------------------------------------------------------------ mapping / helpers

    private Map<String, Object> resource(String tenantId, ScimDirectoryRepository.ScimAccountRow row) {
        Map<String, Object> resource = new LinkedHashMap<>();
        resource.put("schemas", List.of(ScimSupport.USER_SCHEMA, ScimSupport.USER_EXTENSION));
        resource.put("id", row.id());
        resource.put("userName", row.username());
        resource.put("displayName", row.displayName());
        resource.put("name", Map.of("formatted", row.displayName() == null ? "" : row.displayName()));
        if (row.email() != null) {
            resource.put("emails", List.of(Map.of("value", row.email(), "primary", true)));
        }
        if (row.phone() != null) {
            resource.put("phoneNumbers", List.of(Map.of("value", row.phone(), "primary", true)));
        }
        resource.put("active", AccountStatus.ACTIVE.name().equals(row.status()));
        resource.put("roles", List.of(Map.of("value", row.role())));
        Map<String, Object> extension = new LinkedHashMap<>();
        extension.put("orgUnitId", row.orgUnitId());
        if (row.orgUnitId() != null) {
            extension.put("orgUnitName",
                    orgUnits.namesByIds(tenantId, List.of(row.orgUnitId())).get(row.orgUnitId()));
        }
        resource.put(ScimSupport.USER_EXTENSION, extension);
        resource.put("meta", ScimSupport.meta("User", row.createdAt(), row.updatedAt(),
                "/scim/v2/Users/" + row.id()));
        return resource;
    }

    private Map<String, Object> update(String tenantId, ScimDirectoryRepository.ScimAccountRow existing,
                                       Map<String, Object> fields, String actor, String action) {
        if (accounts.updateFields(tenantId, existing.id(), existing.version(), fields) == 0) {
            throw ScimException.conflict("account was modified concurrently, please retry");
        }
        auditService.record(tenantId, actor, action,
                "SCIM user " + existing.username() + " updated (" + existing.id() + ")");
        return resource(tenantId, requireUser(tenantId, existing.id()));
    }

    private Map<String, Object> mutableFields(String displayName, String email, String phone,
                                              AccountRole role, boolean active, String orgUnitId) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("display_name", displayName);
        fields.put("email", Texts.blankToNull(email));
        fields.put("phone", Texts.blankToNull(phone));
        fields.put("role", role.name());
        fields.put("status", active ? AccountStatus.ACTIVE.name() : AccountStatus.DISABLED.name());
        fields.put("org_unit_id", orgUnitId);
        fields.put("updated_at", TimeUtil.now());
        return fields;
    }

    private ScimDirectoryRepository.ScimAccountRow requireUser(String tenantId, String id) {
        return directory.findAccount(tenantId, id)
                .orElseThrow(() -> ScimException.notFound("User not found: " + id));
    }

    private String displayName(Map<String, Object> body, String fallback) {
        String value = ScimSupport.str(body, "displayName");
        if (value == null) {
            value = ScimSupport.nestedString(body, "name", "formatted");
        }
        return value == null ? fallback : value;
    }

    private AccountRole resolveRole(Map<String, Object> body, AccountRole fallback) {
        String role = ScimSupport.firstValue(body, "roles");
        if (role == null) {
            role = ScimSupport.str(body, "role");
        }
        return role == null ? fallback : parseRole(role);
    }

    private AccountRole parseRole(String raw) {
        try {
            return AccountRole.parse(raw);
        } catch (RuntimeException ex) {
            throw ScimException.badRequest("unsupported role: " + raw);
        }
    }

    private String resolveOrgUnit(String tenantId, Map<String, Object> body, String fallback) {
        String orgUnitId = ScimSupport.extensionString(body, ScimSupport.USER_EXTENSION, "orgUnitId");
        if (orgUnitId == null) {
            orgUnitId = ScimSupport.str(body, "orgUnitId");
        }
        return orgUnitId == null ? fallback : validateOrgUnit(tenantId, orgUnitId);
    }

    private String validateOrgUnit(String tenantId, String orgUnitId) {
        orgUnits.findById(tenantId, orgUnitId)
                .orElseThrow(() -> ScimException.badRequest("orgUnitId does not exist: " + orgUnitId));
        return orgUnitId;
    }

    private void validateUsername(String username) {
        if (username == null || username.isBlank() || username.length() > 64
                || username.chars().anyMatch(Character::isWhitespace)) {
            throw ScimException.badRequest("invalid userName");
        }
    }

    private static String valueOrDefault(String value, String fallback) {
        return value == null ? fallback : value;
    }

    private static String extractArrayValue(Object value) {
        if (value instanceof List<?> list && !list.isEmpty()) {
            return ScimSupport.valueText(list.get(0));
        }
        return ScimSupport.valueText(value);
    }
}
