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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.suhong.jiuding.common.error.BizException;
import com.suhong.jiuding.tenant.api.TenantCreateRequest;
import com.suhong.jiuding.tenant.domain.IsolationMode;
import com.suhong.jiuding.tenant.domain.Plan;
import com.suhong.jiuding.tenant.domain.TenantSource;
import com.suhong.jiuding.tenant.domain.TenantStatus;
import com.suhong.jiuding.tenant.repository.TenantRow;
import com.suhong.jiuding.tenant.support.Texts;
import com.suhong.jiuding.tenant.support.TimeUtil;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** Validates API/CSV input and maps it onto database rows and update column maps. */
@Component
public class TenantAssembler {

    private static final String DEFAULT_REGION = "cn-shanghai";

    private final ObjectMapper objectMapper;
    private final Validator validator;

    public TenantAssembler(ObjectMapper objectMapper, Validator validator) {
        this.objectMapper = objectMapper;
        this.validator = validator;
    }

    public void validate(TenantCreateRequest request) {
        if (request == null) {
            throw BizException.invalid("request body is required");
        }
        Set<ConstraintViolation<TenantCreateRequest>> violations = validator.validate(request);
        if (!violations.isEmpty()) {
            String detail = violations.stream()
                    .map(violation -> violation.getPropertyPath() + " " + violation.getMessage())
                    .sorted()
                    .collect(Collectors.joining("; "));
            throw BizException.invalid(detail);
        }
    }

    /** Builds a brand new DRAFT tenant row. */
    public TenantRow build(TenantCreateRequest request, TenantSource fallbackSource) {
        validate(request);
        var now = TimeUtil.now();
        Plan plan = Plan.parseOrDefault(request.plan());
        IsolationMode isolationMode = IsolationMode.parseOrDefault(request.isolationMode());
        TenantSource source = TenantSource.parseOrDefault(request.source(), fallbackSource);
        return new TenantRow(
                UUID.randomUUID().toString(),
                request.tenantCode().trim(),
                request.name().trim(),
                Texts.blankToNull(request.contactName()),
                Texts.blankToNull(request.contactEmail()),
                Texts.blankToNull(request.contactPhone()),
                plan.name(),
                defaultRegion(request.region()),
                isolationMode.name(),
                TenantStatus.DRAFT.name(),
                null,
                null,
                request.quotaSeats(),
                request.quotaStorageGb(),
                request.quotaApiRpm(),
                request.quotaOrgUnits(),
                source.name(),
                attributesJson(request.attributes()),
                null,
                now,
                now,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                0L);
    }

    /** Builds the mutable column map for {@code PUT /api/tenants/{id}}. */
    public Map<String, Object> updateFields(TenantRow existing, TenantCreateRequest request) {
        validate(request);
        if (!existing.tenantCode().equals(request.tenantCode().trim())) {
            throw BizException.invalid("tenantCode is immutable and cannot be changed");
        }
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("name", request.name().trim());
        fields.put("contact_name", Texts.blankToNull(request.contactName()));
        fields.put("contact_email", Texts.blankToNull(request.contactEmail()));
        fields.put("contact_phone", Texts.blankToNull(request.contactPhone()));
        fields.put("plan", Plan.parseOrDefault(request.plan()).name());
        fields.put("region", defaultRegion(request.region()));
        fields.put("isolation_mode", IsolationMode.parseOrDefault(request.isolationMode()).name());
        fields.put("quota_seats", request.quotaSeats());
        fields.put("quota_storage_gb", request.quotaStorageGb());
        fields.put("quota_api_rpm", request.quotaApiRpm());
        fields.put("quota_org_units", request.quotaOrgUnits());
        fields.put("attributes_json", attributesJson(request.attributes()));
        return fields;
    }

    /** Converts one parsed CSV row into the canonical request DTO (still to be validated). */
    public TenantCreateRequest fromCsvRow(CsvTenantParser.CsvRow row) {
        Map<String, String> values = row.values();
        return new TenantCreateRequest(
                Texts.blankToNull(values.get("tenantcode")),
                Texts.blankToNull(values.get("name")),
                Texts.blankToNull(values.get("contactname")),
                Texts.blankToNull(values.get("contactemail")),
                Texts.blankToNull(values.get("contactphone")),
                Texts.blankToNull(values.get("plan")),
                Texts.blankToNull(values.get("region")),
                Texts.blankToNull(values.get("isolationmode")),
                intOrNull(values.get("quotaseats"), "quotaSeats"),
                intOrNull(values.get("quotastoragegb"), "quotaStorageGb"),
                intOrNull(values.get("quotapirm"), "quotaApiRpm"),
                intOrNull(values.get("quotaorgunits"), "quotaOrgUnits"),
                Texts.blankToNull(values.get("source")),
                null,
                null);
    }

    public String attributesJson(Map<String, Object> attributes) {
        if (attributes == null || attributes.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(attributes);
        } catch (JsonProcessingException ex) {
            throw BizException.invalid("attributes must be a JSON object");
        }
    }

    private Integer intOrNull(String raw, String field) {
        String value = Texts.blankToNull(raw);
        if (value == null) {
            return null;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ex) {
            throw BizException.invalid(field + " must be an integer, got '" + raw + "'");
        }
    }

    private String defaultRegion(String raw) {
        return Texts.defaultIfBlank(raw, DEFAULT_REGION);
    }
}
