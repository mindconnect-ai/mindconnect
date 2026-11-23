package ai.mindconnect.chatui.ui.component;

import ai.mindconnect.agent.loop.domain.TurnMessage;
import ai.mindconnect.agent.management.api.ManagedMessage;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The reasoning a persisted assistant message carries, for showing. The
 * runtime writes it as {@code metadata.thinking} with {@code thinkingMs}
 * beside it, on the answer and on a tool-call message alike; a tool-call
 * message written before that convention has readable text only inside its
 * thinking blocks, which are read as the fallback.
 */
final class Thoughts {

    /** Metadata of a tool-call message: the model's thinking blocks, each with its text. */
    private static final String THINKING_BLOCKS = "llm.thinkingBlocks";

    /** One thought: its text and, when recorded, how long the model took over it. */
    record Thought(String text, Long durationMs) { }

    private Thoughts() {
    }

    static Optional<Thought> of(ManagedMessage m) {
        Map<String, Object> metadata = m.message().metadata();
        if (metadata.get("thinking") instanceof String text && !text.isBlank()) {
            return Optional.of(new Thought(text, metadata.get("thinkingMs") instanceof Number n ? n.longValue() : null));
        }
        if (m.message().type() != TurnMessage.Type.TOOL_CALL
                || !(metadata.get(THINKING_BLOCKS) instanceof List<?> blocks)) {
            return Optional.empty();
        }
        StringBuilder sb = new StringBuilder();
        for (Object block : blocks) {
            if (!(block instanceof Map<?, ?> fields) || !(fields.get("text") instanceof String text)
                    || text.isBlank()) {
                continue;
            }
            if (!sb.isEmpty()) sb.append("\n\n");
            sb.append(text.trim());
        }
        return sb.isEmpty() ? Optional.empty() : Optional.of(new Thought(sb.toString(), null));
    }

    /** The card for a persisted thought; {@code nodeId} is stable per message so live patches can land on it. */
    static TaskCardComponent card(ManagedMessage m, Thought thought) {
        return TaskCardComponent.historicThinking("task-think-" + m.id(), thought.text(), thought.durationMs());
    }
}
