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
package com.suhong.jiuding.approval.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 30s automation loop: SLA escalation for overdue human approvals and retry of
 * provisioning chains that failed downstream. Both operations are idempotent.
 */
@Component
public class ApprovalScheduler {

    private static final Logger log = LoggerFactory.getLogger(ApprovalScheduler.class);

    private final ApprovalService approvalService;
    private final ProvisioningService provisioningService;

    public ApprovalScheduler(ApprovalService approvalService, ProvisioningService provisioningService) {
        this.approvalService = approvalService;
        this.provisioningService = provisioningService;
    }

    @Scheduled(fixedDelayString = "${tenancy.approval.scan-interval-ms:30000}",
            initialDelayString = "${tenancy.approval.scan-initial-delay-ms:15000}")
    public void scan() {
        try {
            int escalated = approvalService.escalateOverduePending();
            if (escalated > 0) {
                log.info("SLA scheduler escalated {} overdue approval(s)", escalated);
            }
        } catch (RuntimeException ex) {
            log.warn("SLA escalation scan failed: {}", ex.getMessage());
        }
        try {
            int retried = provisioningService.retryPending();
            if (retried > 0) {
                log.info("provisioning scheduler retried {} approval(s)", retried);
            }
        } catch (RuntimeException ex) {
            log.warn("provisioning retry scan failed: {}", ex.getMessage());
        }
    }
}
