package ai.mindconnect.message.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * One logical exchange: a user CHAT message and everything it caused — tool
 * calls, approval traffic, results — up to (and including) the assistant's
 * answering CHAT, or up to the next user CHAT when there is no answer (yet).
 *
 * <p>Maps 1:1 to the {@code turnId} stamped on the messages: approval resumes
 * CONTINUE the turn under the same id, counted by {@link Message#run()}.
 * (Grouping is still derived from the user-CHAT boundary rather than the id,
 * so legacy messages without a turnId land in the right group too.)
 *
 * @param userMessage the CHAT message that opened this turn
 * @param messages    every message of the turn, user message first, in
 *                    sequence order
 */
public record ChatTurn(Message userMessage, List<Message> messages) {

    public ChatTurn {
        messages = List.copyOf(messages);
    }

    /** The turn's id — stable across approval resumes. Null only on legacy data. */
    public UUID turnId() {
        return userMessage.turnId();
    }

    /** The highest loop run seen in this turn — 0 until the first approval resume. */
    public int lastRun() {
        return messages.stream().mapToInt(Message::runOrZero).max().orElse(0);
    }

    /** The assistant's final CHAT of this turn, empty while the turn is open. */
    public Optional<Message> assistantAnswer() {
        for (int i = messages.size() - 1; i >= 0; i--) {
            Message message = messages.get(i);
            if (message.type() == MessageType.CHAT
                    && message.senderType() != ParticipantType.USER) {
                return Optional.of(message);
            }
        }
        return Optional.empty();
    }

    /** No assistant answer yet — running, waiting for tools, or waiting for a human. */
    public boolean open() {
        return assistantAnswer().isEmpty();
    }

    /** Whether this turn holds a TOOL_RESULT for {@code callId} (pairing runs on metadata). */
    public boolean hasToolResult(String callId) {
        return messages.stream()
                .anyMatch(m -> m.type() == MessageType.TOOL_RESULT
                        && callId.equals(m.metadata().get("callId")));
    }
}
