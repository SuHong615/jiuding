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

import com.suhong.jiuding.approval.api.ApprovalView;
import com.suhong.jiuding.approval.api.NodeView;
import com.suhong.jiuding.approval.api.TimelineItem;
import com.suhong.jiuding.approval.domain.ApprovalEntity;
import com.suhong.jiuding.approval.domain.ApprovalNodeEntity;
import com.suhong.jiuding.approval.repo.ApprovalRepository;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/** Maps persistence entities to the API contract records. */
@Component
public class ApprovalViewMapper {

    private final ApprovalRepository repository;

    public ApprovalViewMapper(ApprovalRepository repository) {
        this.repository = repository;
    }

    public ApprovalView toView(ApprovalEntity entity) {
        List<NodeView> nodes = repository.findNodes(entity.getId()).stream().map(this::toNodeView).toList();
        List<TimelineItem> timeline = repository.findTimeline(entity.getId());
        return new ApprovalView(
                entity.getId(),
                entity.getTenantId(),
                entity.getTenantCode(),
                entity.getPlan(),
                entity.getState() == null ? null : entity.getState().name(),
                entity.getCurrentNode(),
                entity.getRiskLevel(),
                entity.getRiskScore(),
                entity.isAutoDecided(),
                entity.getDecidedBy(),
                entity.getDecisionNote(),
                iso(entity.getSlaDueAt()),
                entity.isEscalated(),
                List.copyOf(entity.getReasons()),
                nodes,
                timeline,
                iso(entity.getCreatedAt()),
                iso(entity.getUpdatedAt()));
    }

    private NodeView toNodeView(ApprovalNodeEntity node) {
        return new NodeView(
                node.getNode(),
                node.getNodeType() == null ? null : node.getNodeType().name(),
                node.getState() == null ? null : node.getState().name(),
                node.getAssigneeRole(),
                node.getDecidedBy(),
                iso(node.getDecidedAt()),
                node.getComment(),
                node.getSlaHours());
    }

    private static String iso(Instant instant) {
        return instant == null ? null : instant.toString();
    }
}
