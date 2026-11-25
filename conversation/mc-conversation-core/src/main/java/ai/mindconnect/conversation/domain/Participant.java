package ai.mindconnect.conversation.domain;

import java.time.Instant;
import java.util.Map;

/**
 * Someone or something that takes part in a conversation.
 *
 * @param refId    who it is where it comes from: a user's or agent's id, a phone number, an id in Teams
 * @param metadata what else is known about it, such as its {@code role}
 * @param leftAt   when it left; null while it takes part
 */
public record Participant(
        ParticipantId id,
        ConversationId conversationId,
        ParticipantType type,
        String refId,
        String displayName,
        Map<String, Object> metadata,
        Instant joinedAt,
        Instant leftAt
) {

    public Participant {
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    /** Whether it still takes part. */
    public boolean present() {
        return leftAt == null;
    }

    public Participant leaving(Instant at) {
        return new Participant(id, conversationId, type, refId, displayName, metadata, joinedAt, at);
    }
}
