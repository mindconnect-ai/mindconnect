package ai.mindconnect.llm.gateway.app;

import ai.mindconnect.llm.runtime.api.domain.LlmMessage;
import ai.mindconnect.llm.runtime.api.domain.LlmRequest;
import ai.mindconnect.llm.runtime.api.domain.MessageRole;
import ai.mindconnect.llm.runtime.api.domain.ToolCall;
import ai.mindconnect.llm.runtime.api.domain.ToolDefinition;

import java.util.List;
import java.util.Map;

/**
 * A chat call as JSON: the config by name and the conversation as text.
 * {@link LlmMessage} carries media blocks as a sealed type with no type tag
 * on the wire, so the REST form keeps to text and tool calls.
 *
 * @param temperature     {@code null}: the config's default
 * @param maxOutputTokens {@code null}: the config's default
 */
record ChatRequest(
        String configName,
        List<Message> messages,
        List<ToolDefinition> tools,
        Double temperature,
        Integer maxOutputTokens,
        Map<String, Object> additionalParams
) {

    /** One message: text, or for an assistant the tool calls it made, for a tool the call it answers. */
    record Message(MessageRole role, String content, String toolCallId, List<ToolCall> toolCalls) {

        LlmMessage toLlmMessage() {
            if (toolCalls != null && !toolCalls.isEmpty()) {
                return LlmMessage.assistantWithToolCalls(toolCalls);
            }
            return switch (role) {
                case SYSTEM -> LlmMessage.system(content);
                case USER -> LlmMessage.user(content);
                case ASSISTANT -> LlmMessage.assistant(content);
                case TOOL -> LlmMessage.tool(toolCallId, content);
            };
        }
    }

    LlmRequest toLlmRequest(boolean stream) {
        if (configName == null || configName.isBlank()) {
            throw new IllegalArgumentException("A chat call names the config it goes to: configName");
        }
        if (messages == null || messages.isEmpty()) {
            throw new IllegalArgumentException("A chat call needs at least one message");
        }
        return new LlmRequest(configName, messages.stream().map(Message::toLlmMessage).toList(),
                tools == null ? List.of() : tools,
                temperature == null ? -1 : temperature,
                maxOutputTokens == null ? -1 : maxOutputTokens,
                stream, additionalParams);
    }
}
