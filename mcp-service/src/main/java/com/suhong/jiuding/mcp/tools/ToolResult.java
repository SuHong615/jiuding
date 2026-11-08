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
package com.suhong.jiuding.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Outcome of {@code tools/call}. Rendered as
 * {@code {content:[{type:"text",text:"<json>"}], structuredContent:{...}, isError:bool}}
 * exactly as docs/05 requires.
 */
public record ToolResult(boolean isError, JsonNode structuredContent) {

    public static ToolResult ok(JsonNode structuredContent) {
        return new ToolResult(false, structuredContent);
    }

    public static ToolResult error(JsonNode structuredContent) {
        return new ToolResult(true, structuredContent);
    }

    public ObjectNode toJson(ObjectMapper objectMapper) {
        ObjectNode result = objectMapper.createObjectNode();
        result.put("isError", isError);
        ObjectNode textItem = result.putArray("content").addObject();
        textItem.put("type", "text");
        textItem.put("text", structuredContent == null ? "{}" : structuredContent.toString());
        result.set("structuredContent", normalizeStructuredContent(objectMapper));
        return result;
    }

    /**
     * MCP requires {@code structuredContent} to be a JSON object. Several downstream
     * endpoints (org-units tree/flat, pending approvals) return bare arrays, so wrap
     * them as {@code {count, items}} to keep the protocol contract stable for clients.
     */
    private JsonNode normalizeStructuredContent(ObjectMapper objectMapper) {
        if (structuredContent == null) {
            return objectMapper.createObjectNode();
        }
        if (structuredContent.isArray()) {
            ObjectNode wrapper = objectMapper.createObjectNode();
            wrapper.put("count", structuredContent.size());
            wrapper.set("items", structuredContent);
            return wrapper;
        }
        return structuredContent;
    }
}
