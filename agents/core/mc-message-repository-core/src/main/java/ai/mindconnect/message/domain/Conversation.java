package ai.mindconnect.message.domain;

import java.time.Instant;
import java.util.List;

public record Conversation(
        ConversationId id,
        String topic,
        ConversationType type,
        ConversationStatus status,
        List<Participant> participants,
        Instant createdAt,
        Instant updatedAt
) {
    /**
     * A new, open conversation under an id the caller chose — the participants
     * carry that id already, which is why the caller and not this factory
     * draws it ({@code ConversationId.random()}).
     */
    public static Conversation create(ConversationId id, String topic,
                                       ConversationType type, List<Participant> participants) {
        for (Participant p : participants) {
            if (!p.conversationId().equals(id)) {
                throw new IllegalArgumentException(
                        "Participant " + p.id() + " does not belong to conversation " + id);
            }
        }
        Instant now = Instant.now();
        return new Conversation(id, topic, type, ConversationStatus.OPEN, participants, now, now);
    }

}
