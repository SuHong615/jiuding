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
package com.suhong.jiuding.cli;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Small JSON helper shared by every command (Jackson, already on the classpath). */
public final class Json {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private static final ObjectWriter PRETTY = MAPPER.writerWithDefaultPrettyPrinter();

    private Json() {
    }

    public static ObjectMapper mapper() {
        return MAPPER;
    }

    public static JsonNode parse(String text) {
        if (text == null || text.isBlank()) {
            return NullNode.getInstance();
        }
        try {
            return MAPPER.readTree(text);
        } catch (JsonProcessingException e) {
            throw new CliUsageException("JSON 解析失败: " + e.getOriginalMessage());
        }
    }

    public static String pretty(JsonNode node) {
        if (node == null) {
            return "null";
        }
        try {
            return PRETTY.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("JSON 序列化失败", e);
        }
    }

    public static String compact(JsonNode node) {
        if (node == null) {
            return "null";
        }
        try {
            return MAPPER.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("JSON 序列化失败", e);
        }
    }

    public static String toJson(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("JSON 序列化失败", e);
        }
    }

    public static ObjectNode object() {
        return JsonNodeFactory.instance.objectNode();
    }

    /** Reads a text field, returning {@code fallback} when the node/field is missing or null. */
    public static String text(JsonNode node, String field, String fallback) {
        if (node == null || !node.isObject()) {
            return fallback;
        }
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return fallback;
        }
        return value.asText();
    }

    public static int intValue(JsonNode node, String field, int fallback) {
        if (node == null || !node.isObject()) {
            return fallback;
        }
        JsonNode value = node.get(field);
        if (value == null || value.isNull() || !value.isNumber()) {
            return fallback;
        }
        return value.asInt();
    }

    public static JsonNode field(JsonNode node, String field) {
        if (node == null || !node.isObject()) {
            return NullNode.getInstance();
        }
        JsonNode value = node.get(field);
        return value == null ? NullNode.getInstance() : value;
    }

    /**
     * List-ish payloads come back either as a bare array or wrapped in
     * {@code {items:[...]}} (PageResult). Returns the array node in both cases.
     */
    public static JsonNode items(JsonNode data) {
        if (data == null || data.isNull()) {
            return JsonNodeFactory.instance.arrayNode();
        }
        if (data.isArray()) {
            return data;
        }
        JsonNode items = data.get("items");
        return items != null && items.isArray() ? items : JsonNodeFactory.instance.arrayNode();
    }
}
