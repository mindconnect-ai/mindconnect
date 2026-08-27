package ai.mindconnect.llm.domain;

import java.util.Map;

/**
 * A single tool/function call requested by the model.
 * <p>
 * {@code thoughtSignature} is a Gemini-specific opaque token. Gemini 2.5+
 * attaches a {@code thoughtSignature} to every {@code functionCall} part it
 * emits and <em>requires</em> that signature to be echoed back when the call
 * is replayed in conversation history — otherwise the API rejects the request
 * with HTTP 400 ("Function call is missing a thought_signature"). It is null
 * for every other provider and ignored by them.
 */
public record ToolCall(
        String id,
        String name,
        Map<String, Object> arguments,
        String thoughtSignature
) {
    /** Convenience constructor for providers that have no thought signature. */
    public ToolCall(String id, String name, Map<String, Object> arguments) {
        this(id, name, arguments, null);
    }
}
