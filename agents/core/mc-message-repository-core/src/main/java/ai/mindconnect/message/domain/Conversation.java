package ai.mindconnect.message.domain;

import ai.mindconnect.common.Namespace;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record Conversation(
        UUID id,
        Namespace namespace,
        String topic,
        ConversationType type,
        ConversationStatus status,
        List<Participant> participants,
        Instant createdAt,
        Instant updatedAt
) {
    public static Conversation create(Namespace namespace, String topic,
                                       ConversationType type, List<Participant> participants) {
        Instant now = Instant.now();
        return new Conversation(UUID.randomUUID(), namespace, topic, type,
                ConversationStatus.OPEN, participants, now, now);
    }
}
