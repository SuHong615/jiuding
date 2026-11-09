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
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Pure unit tests for workflow definition validation (required fields, refs, cycles). */
class WorkflowDefinitionValidatorTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final WorkflowDefinitionValidator validator = new WorkflowDefinitionValidator();

    @Test
    void validDefinitionPasses() throws Exception {
        WorkflowValidationResult result = validate("""
                {"nodes":[
                  {"id":"risk","type":"AUTO_CHECK","skill":"risk-review","next":"approval"},
                  {"id":"approval","type":"HUMAN","role":"APPROVER","slaHours":24,"next":null}]}
                """);
        assertThat(result.valid()).isTrue();
        assertThat(result.nodeCount()).isEqualTo(2);
        assertThat(result.errors()).isEmpty();
    }

    @Test
    void emptyNodesRejected() throws Exception {
        WorkflowValidationResult result = validate("{\"nodes\":[]}");
        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).anyMatch(error -> error.contains("non-empty array"));
    }

    @Test
    void missingNodeIdRejected() throws Exception {
        WorkflowValidationResult result = validate("{\"nodes\":[{\"type\":\"HUMAN\"}]}");
        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).anyMatch(error -> error.contains("requires an id"));
    }

    @Test
    void duplicateNodeIdRejected() throws Exception {
        WorkflowValidationResult result = validate("""
                {"nodes":[
                  {"id":"a","type":"HUMAN"},
                  {"id":"a","type":"HUMAN"}]}
                """);
        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).anyMatch(error -> error.contains("duplicate node id"));
    }

    @Test
    void unknownNodeTypeRejected() throws Exception {
        WorkflowValidationResult result = validate("{\"nodes\":[{\"id\":\"a\",\"type\":\"SCRIPT\"}]}");
        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).anyMatch(error -> error.contains("unsupported type"));
    }

    @Test
    void unknownNextReferenceRejected() throws Exception {
        WorkflowValidationResult result = validate("""
                {"nodes":[
                  {"id":"a","type":"HUMAN","next":"missing"},
                  {"id":"b","type":"HUMAN"}]}
                """);
        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).anyMatch(error -> error.contains("unknown next node"));
    }

    @Test
    void selfReferenceRejected() throws Exception {
        WorkflowValidationResult result = validate("{\"nodes\":[{\"id\":\"a\",\"type\":\"HUMAN\",\"next\":\"a\"}]}");
        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).anyMatch(error -> error.contains("cannot reference itself"));
    }

    @Test
    void cycleDetected() throws Exception {
        WorkflowValidationResult result = validate("""
                {"nodes":[
                  {"id":"a","type":"HUMAN","next":"b"},
                  {"id":"b","type":"HUMAN","next":"a"}]}
                """);
        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).anyMatch(error -> error.contains("cycle detected"));
    }

    @Test
    void negativeHumanSlaRejected() throws Exception {
        WorkflowValidationResult result = validate("""
                {"nodes":[{"id":"a","type":"HUMAN","role":"APPROVER","slaHours":-1}]}
                """);
        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).anyMatch(error -> error.contains("slaHours must be positive"));
    }

    @Test
    void slaNodeWithoutHoursRejected() throws Exception {
        WorkflowValidationResult result = validate("""
                {"nodes":[{"id":"sla","type":"SLA"},{"id":"a","type":"HUMAN"}]}
                """);
        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).anyMatch(error -> error.contains("requires slaHours"));
    }

    @Test
    void parallelWithoutChildrenIsOnlyAWarning() throws Exception {
        WorkflowValidationResult result = validate("""
                {"nodes":[{"id":"stage","type":"PARALLEL","next":"approval"},
                          {"id":"approval","type":"HUMAN","role":"APPROVER","slaHours":24}]}
                """);
        assertThat(result.valid()).isTrue();
        assertThat(result.warnings()).anyMatch(warning -> warning.contains("PARALLEL"));
    }

    @Test
    void unreachableNodeIsOnlyAWarning() throws Exception {
        WorkflowValidationResult result = validate("""
                {"nodes":[
                  {"id":"a","type":"HUMAN","role":"APPROVER"},
                  {"id":"orphan","type":"HUMAN","role":"APPROVER"}]}
                """);
        assertThat(result.valid()).isTrue();
        assertThat(result.warnings()).anyMatch(warning -> warning.contains("not reachable"));
    }

    @Test
    void nonObjectNodeEntryRejected() throws Exception {
        WorkflowValidationResult result = validate("{\"nodes\":[42]}");
        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).anyMatch(error -> error.contains("must be a JSON object"));
    }

    private WorkflowValidationResult validate(String json) throws Exception {
        JsonNode definition = objectMapper.readTree(json);
        return validator.validate(definition);
    }
}
