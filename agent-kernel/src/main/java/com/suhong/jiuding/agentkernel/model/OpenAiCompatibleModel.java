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
package com.suhong.jiuding.agentkernel.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import reactor.core.publisher.Flux;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenAI-compatible chat model adapter (works with OpenAI, DeepSeek, Qwen,
 * Ollama's /v1 endpoint and any other compatible gateway).
 *
 * <p>Non-streaming request/response is used deliberately: the platform needs
 * deterministic, easy-to-audit agent traces more than token-level streaming, and
 * every compatible provider supports the non-streaming variant.
 */
public class OpenAiCompatibleModel implements Model {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String modelName;
    private final String baseUrl;
    private final String apiKey;
    private final double temperature;
    private final int maxTokens;
    private final Duration timeout;
    private final HttpClient httpClient;

    public OpenAiCompatibleModel(String modelName, String baseUrl, String apiKey,
                                 double temperature, int maxTokens, Duration timeout) {
        this.modelName = modelName;
        this.baseUrl = trimTrailingSlash(baseUrl);
        this.apiKey = apiKey;
        this.temperature = temperature;
        this.maxTokens = maxTokens;
        this.timeout = timeout;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    @Override
    public String getModelName() {
        return modelName;
    }

    @Override
    public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
        return Flux.defer(() -> {
            try {
                return Flux.just(call(messages, tools));
            } catch (Exception e) {
                return Flux.error(e);
            }
        });
    }

    private ChatResponse call(List<Msg> messages, List<ToolSchema> tools) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", modelName);
        body.put("stream", false);
        body.put("temperature", temperature);
        body.put("max_tokens", maxTokens);
        body.put("messages", toWireMessages(messages));
        if (tools != null && !tools.isEmpty()) {
            body.put("tools", tools.stream().map(this::toWireTool).toList());
        }

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/chat/completions"))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body), StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            throw new IllegalStateException("model call failed: HTTP " + response.statusCode() + " " + response.body());
        }
        JsonNode root = MAPPER.readTree(response.body());
        JsonNode choice = root.path("choices").path(0);
        JsonNode message = choice.path("message");
        String finishReason = choice.path("finish_reason").asText("stop");

        List<ContentBlock> blocks = new ArrayList<>();
        String text = message.path("content").asText("");
        if (!text.isBlank()) {
            blocks.add(TextBlock.builder().text(text).build());
        }
        JsonNode toolCalls = message.path("tool_calls");
        if (toolCalls.isArray()) {
            for (JsonNode toolCall : toolCalls) {
                String id = toolCall.path("id").asText();
                String name = toolCall.path("function").path("name").asText();
                String arguments = toolCall.path("function").path("arguments").asText("{}");
                Map<String, Object> input = arguments.isBlank() ? Map.of()
                        : MAPPER.readValue(arguments, Map.class);
                blocks.add(ToolUseBlock.builder().id(id).name(name).input(input).content(arguments).build());
            }
        }

        JsonNode usage = root.path("usage");
        ChatUsage chatUsage = new ChatUsage(
                usage.path("prompt_tokens").asInt(0),
                usage.path("completion_tokens").asInt(0),
                usage.path("total_tokens").asInt(0),
                0.0);

        return ChatResponse.builder()
                .id(root.path("id").asText())
                .content(blocks)
                .usage(chatUsage)
                .finishReason(finishReason)
                .build();
    }

    private List<Map<String, Object>> toWireMessages(List<Msg> messages) {
        List<Map<String, Object>> wire = new ArrayList<>();
        for (Msg msg : messages) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("role", switch (msg.getRole()) {
                case SYSTEM -> "system";
                case USER -> "user";
                case ASSISTANT -> "assistant";
                case TOOL -> "tool";
            });
            item.put("content", msg.getTextContent());
            wire.add(item);
        }
        return wire;
    }

    private Map<String, Object> toWireTool(ToolSchema schema) {
        Map<String, Object> function = new LinkedHashMap<>();
        function.put("name", schema.getName());
        function.put("description", schema.getDescription());
        function.put("parameters", schema.getParameters() == null ? Map.of() : schema.getParameters());
        Map<String, Object> tool = new LinkedHashMap<>();
        tool.put("type", "function");
        tool.put("function", function);
        return tool;
    }

    private static String trimTrailingSlash(String value) {
        if (value == null || value.isBlank()) {
            return "https://api.openai.com/v1";
        }
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    /** Factory that keeps model selection configuration-driven. */
    public static Model create(ModelSettings settings) {
        if (!settings.openAiEnabled()) {
            return new ScriptedModel(settings.modelName(), ScriptedResponses.echo());
        }
        return new OpenAiCompatibleModel(settings.modelName(), settings.baseUrl(), settings.apiKey(),
                settings.temperature(), settings.maxTokens(), Duration.ofSeconds(settings.timeoutSeconds()));
    }

    /** Resolved model configuration (bound from environment in agent-service). */
    public record ModelSettings(String provider, String baseUrl, String apiKey, String modelName,
                                double temperature, int maxTokens, int timeoutSeconds) {
        public boolean openAiEnabled() {
            return provider != null && !provider.isBlank()
                    && !"scripted".equalsIgnoreCase(provider)
                    && apiKey != null && !apiKey.isBlank();
        }
    }

    /** Default scripted responder: echoes the last user text (fallback safety net). */
    static final class ScriptedResponses {
        private ScriptedResponses() {
        }

        static java.util.function.Function<List<Msg>, ChatResponse> echo() {
            return messages -> {
                String last = messages.isEmpty() ? "" : messages.get(messages.size() - 1).getTextContent();
                return ScriptedModel.text("scripted-model: " + last);
            };
        }
    }
}
