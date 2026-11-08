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
package com.suhong.jiuding.approval;

import com.suhong.jiuding.approval.config.ApprovalProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Approval workflow service (port 8082).
 *
 * <p>Owns the approval state machine, auto-decision routing (agent-service with a
 * deterministic local fallback), the post-approval provisioning hand-off and the
 * SLA escalation scheduler. Shared authentication / CORS / error envelopes come
 * from {@code tenancy-common} via auto-configuration.
 */
@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(ApprovalProperties.class)
public class ApprovalServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(ApprovalServiceApplication.class, args);
    }
}
