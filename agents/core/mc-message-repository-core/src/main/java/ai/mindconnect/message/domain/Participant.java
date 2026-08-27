package ai.mindconnect.message.domain;

import java.time.Instant;
import java.util.UUID;

public record Participant(
        UUID id,
        UUID conversationId,
        ParticipantType type,
        String refId,
        String displayName,
        Instant joinedAt
) {
    public static Participant user(UUID conversationId, String userId, String displayName) {
        return new Participant(UUID.randomUUID(), conversationId,
                ParticipantType.USER, userId, displayName, Instant.now());
    }

    public static Participant agent(UUID conversationId, String agentId, String displayName) {
        return new Participant(UUID.randomUUID(), conversationId,
                ParticipantType.AGENT, agentId, displayName, Instant.now());
    }
}
