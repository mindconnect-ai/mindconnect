package ai.mindconnect.llm.domain;

import java.util.List;
import java.util.Map;

public record LlmRequest(
        String configName,
        List<LlmMessage> messages,
        List<ToolDefinition> tools,
        double temperature,
        int maxOutputTokens,
        boolean stream,
        /**
         * Per-request provider-specific parameters (e.g. {@code thinking},
         * {@code effort} for Anthropic, {@code reasoning_effort} for OpenAI).
         * Merged over {@link LlmConfig#additionalParams()} at call time — request
         * keys win. Each adapter reads only the keys it understands. Never null
         * (defaults to an empty map); other providers ignore unknown keys.
         */
        Map<String, Object> additionalParams
) {
    public LlmRequest {
        if (additionalParams == null) additionalParams = Map.of();
    }

    public static LlmRequest of(String configName, List<LlmMessage> messages) {
        return new LlmRequest(configName, messages, List.of(), -1, -1, false, Map.of());
    }

    public static LlmRequest of(String configName, List<LlmMessage> messages, List<ToolDefinition> tools) {
        return new LlmRequest(configName, messages, tools, -1, -1, false, Map.of());
    }

    public static LlmRequest streaming(String configName, List<LlmMessage> messages) {
        return new LlmRequest(configName, messages, List.of(), -1, -1, true, Map.of());
    }

    public static LlmRequest streaming(String configName, List<LlmMessage> messages, List<ToolDefinition> tools) {
        return new LlmRequest(configName, messages, tools, -1, -1, true, Map.of());
    }

    public LlmRequest withMessages(List<LlmMessage> newMessages) {
        return new LlmRequest(configName, newMessages, tools, temperature, maxOutputTokens, stream, additionalParams);
    }

    public LlmRequest withAdditionalParams(Map<String, Object> params) {
        return new LlmRequest(configName, messages, tools, temperature, maxOutputTokens, stream, params);
    }
}
