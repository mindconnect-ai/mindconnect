package ai.mindconnect.chatui.ui.component;

import ai.mindconnect.message.domain.Message;
import ai.mindconnect.message.domain.MessageType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Optional;

/**
 * The reasoning a persisted assistant message carries, for showing. The
 * runtime writes it as {@code metadata.thinking} with {@code thinkingMs}
 * beside it, on the answer and on a tool-call message alike; a tool-call
 * message written before that convention has readable text only inside its
 * content's {@code thinkingBlocks}, which is read as the fallback.
 */
final class Thoughts {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** One thought: its text and, when recorded, how long the model took over it. */
    record Thought(String text, Long durationMs) { }

    private Thoughts() {
    }

    static Optional<Thought> of(Message m) {
        Object thinking = m.metadata() == null ? null : m.metadata().get("thinking");
        if (thinking instanceof String text && !text.isBlank()) {
            Object ms = m.metadata().get("thinkingMs");
            return Optional.of(new Thought(text, ms instanceof Number n ? n.longValue() : null));
        }
        if (m.type() != MessageType.TOOL_CALL || m.content() == null) return Optional.empty();
        try {
            JsonNode blocks = MAPPER.readTree(m.content()).path("thinkingBlocks");
            if (!blocks.isArray()) return Optional.empty();
            StringBuilder sb = new StringBuilder();
            for (JsonNode block : blocks) {
                String text = block.path("text").asText("");
                if (text.isBlank()) continue;
                if (!sb.isEmpty()) sb.append("\n\n");
                sb.append(text.trim());
            }
            return sb.isEmpty() ? Optional.empty() : Optional.of(new Thought(sb.toString(), null));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /** The card for a persisted thought; {@code nodeId} is stable per message so live patches can land on it. */
    static TaskCardComponent card(Message m, Thought thought) {
        return TaskCardComponent.historicThinking("task-think-" + m.id().value(), thought.text(), thought.durationMs());
    }
}
