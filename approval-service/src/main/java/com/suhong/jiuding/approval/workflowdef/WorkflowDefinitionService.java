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
package com.suhong.jiuding.approval.workflowdef;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.suhong.jiuding.common.error.BizException;
import com.suhong.jiuding.common.security.AuthContext;
import com.suhong.jiuding.common.security.Principal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Platform-admin workflow definition management (docs/23 §3): CRUD on DRAFT
 * versions, validation, publishing with automatic archiving of the previous
 * published version, and published-definition resolution for the approval engine.
 */
@Service
public class WorkflowDefinitionService {

    private static final Logger log = LoggerFactory.getLogger(WorkflowDefinitionService.class);
    private static final String DEFAULT_CODE = "default";
    private static final Pattern CODE_PATTERN = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$");

    private final WorkflowDefinitionRepository repository;
    private final WorkflowDefinitionValidator validator;
    private final ObjectMapper objectMapper;

    public WorkflowDefinitionService(WorkflowDefinitionRepository repository,
                                     WorkflowDefinitionValidator validator,
                                     ObjectMapper objectMapper) {
        this.repository = repository;
        this.validator = validator;
        this.objectMapper = objectMapper;
    }

    // -------------------------------------------------------------------- CRUD

    public List<WorkflowView> list(String code, String status) {
        requirePlatformAdmin();
        String normalizedStatus = null;
        if (status != null && !status.isBlank()) {
            WorkflowStatus parsed = WorkflowStatus.parse(status);
            if (parsed == null) {
                throw BizException.invalid("unknown workflow status: " + status);
            }
            normalizedStatus = parsed.name();
        }
        String normalizedCode = code == null || code.isBlank() ? null : code.trim();
        return repository.list(normalizedCode, normalizedStatus).stream().map(this::toView).toList();
    }

    public WorkflowView get(String id) {
        requirePlatformAdmin();
        return toView(find(id));
    }

    public WorkflowView create(WorkflowCreateRequest request) {
        requirePlatformAdmin();
        String code = normalizeCode(request.code());
        requireObject(request.definition(), "definition");
        int version = repository.maxVersion(code) + 1;
        Instant now = Instant.now();
        WorkflowDefinitionEntity entity = new WorkflowDefinitionEntity();
        entity.setId(UUID.randomUUID().toString());
        entity.setCode(code);
        entity.setName(request.name() == null || request.name().isBlank() ? code : request.name().trim());
        entity.setVersion(version);
        entity.setStatus(WorkflowStatus.DRAFT);
        entity.setDefinitionJson(write(request.definition()));
        entity.setCreatedBy(currentUsername());
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        repository.insert(entity);
        log.info("workflow definition {} v{} created by {}", code, version, entity.getCreatedBy());
        return toView(entity);
    }

    public WorkflowView update(String id, WorkflowUpdateRequest request) {
        requirePlatformAdmin();
        WorkflowDefinitionEntity entity = find(id);
        if (entity.getStatus() != WorkflowStatus.DRAFT) {
            throw BizException.conflict("only DRAFT workflow definitions can be modified (current: "
                    + entity.getStatus() + ")");
        }
        if (request.name() == null && request.definition() == null) {
            throw BizException.invalid("provide name, definition or both");
        }
        if (request.name() != null && !request.name().isBlank()) {
            entity.setName(request.name().trim());
        }
        if (request.definition() != null) {
            requireObject(request.definition(), "definition");
            entity.setDefinitionJson(write(request.definition()));
        }
        entity.setUpdatedAt(Instant.now());
        if (!repository.update(entity)) {
            throw BizException.conflict("workflow definition " + id + " was modified concurrently");
        }
        return toView(entity);
    }

    public void delete(String id) {
        requirePlatformAdmin();
        WorkflowDefinitionEntity entity = find(id);
        if (entity.getStatus() != WorkflowStatus.DRAFT) {
            throw BizException.conflict("only DRAFT workflow definitions can be deleted (current: "
                    + entity.getStatus() + ")");
        }
        if (!repository.deleteById(id)) {
            throw BizException.notFound("workflow definition not found: " + id);
        }
        log.info("workflow definition {} v{} ({}) deleted", entity.getCode(), entity.getVersion(), id);
    }

    // ------------------------------------------------------ validate / publish

    public WorkflowValidationResult validate(String id) {
        requirePlatformAdmin();
        WorkflowDefinitionEntity entity = find(id);
        return validator.validate(readDefinition(entity));
    }

    @Transactional
    public WorkflowView publish(String id) {
        requirePlatformAdmin();
        WorkflowDefinitionEntity entity = find(id);
        if (entity.getStatus() == WorkflowStatus.PUBLISHED) {
            return toView(entity);
        }
        if (entity.getStatus() == WorkflowStatus.ARCHIVED) {
            throw BizException.invalid("archived workflow definitions cannot be published; create a new version");
        }
        WorkflowValidationResult result = validator.validate(readDefinition(entity));
        if (!result.valid()) {
            throw BizException.invalid("workflow definition invalid: " + result.summary());
        }
        Instant now = Instant.now();
        int archived = repository.archivePublished(entity.getCode(), entity.getId(), now);
        entity.setStatus(WorkflowStatus.PUBLISHED);
        entity.setPublishedAt(now);
        entity.setUpdatedAt(now);
        if (!repository.update(entity)) {
            throw BizException.conflict("workflow definition " + id + " was modified concurrently");
        }
        log.info("workflow definition {} v{} published ({} previous version(s) archived)",
                entity.getCode(), entity.getVersion(), archived);
        return toView(entity);
    }

    // ------------------------------------------------------- engine resolution

    /**
     * Resolves the published definition used to create an approval:
     * explicit {@code workflowCode} first, then code {@code default}, then the most
     * recently published definition. Empty means "use the built-in default flow".
     */
    public Optional<PublishedWorkflow> resolvePublished(String workflowCode) {
        Optional<WorkflowDefinitionEntity> entity;
        if (workflowCode != null && !workflowCode.isBlank()) {
            entity = repository.findPublishedByCode(workflowCode.trim());
        } else {
            entity = repository.findPublishedByCode(DEFAULT_CODE)
                    .or(this::findFirstPublished);
        }
        return entity.flatMap(this::toPublished);
    }

    private Optional<WorkflowDefinitionEntity> findFirstPublished() {
        return repository.findFirstPublished();
    }

    private Optional<PublishedWorkflow> toPublished(WorkflowDefinitionEntity entity) {
        try {
            JsonNode definition = objectMapper.readTree(entity.getDefinitionJson());
            return Optional.of(new PublishedWorkflow(entity, WorkflowDefinitionModel.parse(definition)));
        } catch (Exception ex) {
            log.warn("published workflow {} v{} has an unreadable definition, ignoring: {}",
                    entity.getCode(), entity.getVersion(), ex.getMessage());
            return Optional.empty();
        }
    }

    // ----------------------------------------------------------------- helpers

    private WorkflowDefinitionEntity find(String id) {
        return repository.findById(id)
                .orElseThrow(() -> BizException.notFound("workflow definition not found: " + id));
    }

    private WorkflowView toView(WorkflowDefinitionEntity entity) {
        return new WorkflowView(
                entity.getId(),
                entity.getCode(),
                entity.getName(),
                entity.getVersion(),
                entity.getStatus() == null ? null : entity.getStatus().name(),
                readDefinition(entity),
                entity.getCreatedBy(),
                iso(entity.getCreatedAt()),
                iso(entity.getUpdatedAt()),
                iso(entity.getPublishedAt()));
    }

    private JsonNode readDefinition(WorkflowDefinitionEntity entity) {
        if (entity.getDefinitionJson() == null || entity.getDefinitionJson().isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(entity.getDefinitionJson());
        } catch (Exception ex) {
            log.warn("workflow definition {} v{} JSON is unreadable: {}",
                    entity.getCode(), entity.getVersion(), ex.getMessage());
            return null;
        }
    }

    private String write(JsonNode definition) {
        try {
            return objectMapper.writeValueAsString(definition);
        } catch (Exception ex) {
            throw BizException.invalid("definition cannot be serialized: " + ex.getMessage());
        }
    }

    private String normalizeCode(String raw) {
        if (raw == null || raw.isBlank()) {
            throw BizException.invalid("code is required");
        }
        String code = raw.trim();
        if (!CODE_PATTERN.matcher(code).matches()) {
            throw BizException.invalid(
                    "code must match [A-Za-z0-9][A-Za-z0-9._-]{0,63}");
        }
        return code.toLowerCase(Locale.ROOT);
    }

    private void requireObject(JsonNode definition, String field) {
        if (definition == null || definition.isNull() || !definition.isObject()) {
            throw BizException.invalid(field + " must be a JSON object");
        }
    }

    private void requirePlatformAdmin() {
        Principal principal = AuthContext.require();
        if (!principal.isPlatformAdmin()) {
            throw BizException.forbidden("workflow definition management requires PLATFORM_ADMIN");
        }
    }

    private String currentUsername() {
        Principal principal = AuthContext.get();
        return principal == null || principal.username() == null ? "system" : principal.username();
    }

    private static String iso(Instant instant) {
        return instant == null ? null : instant.toString();
    }
}
