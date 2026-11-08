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

import com.suhong.jiuding.tenant.repository.AuditRepository;
import com.suhong.jiuding.tenant.repository.AuditRow;
import com.suhong.jiuding.tenant.support.Texts;
import com.suhong.jiuding.tenant.support.TimeUtil;
import org.springframework.stereotype.Service;

import java.util.UUID;

/** Writes the immutable tenant audit trail. */
@Service
public class AuditService {

    private static final int ACTOR_MAX = 64;
    private static final int ACTION_MAX = 64;
    private static final int DETAIL_MAX = 1000;

    private final AuditRepository auditRepository;

    public AuditService(AuditRepository auditRepository) {
        this.auditRepository = auditRepository;
    }

    public void record(String tenantId, String actor, String action, String detail) {
        auditRepository.insert(new AuditRow(
                UUID.randomUUID().toString(),
                tenantId,
                TimeUtil.now(),
                Texts.truncate(Texts.defaultIfBlank(actor, "system"), ACTOR_MAX),
                Texts.truncate(Texts.defaultIfBlank(action, "UNKNOWN"), ACTION_MAX),
                Texts.truncate(detail, DETAIL_MAX)));
    }
}
