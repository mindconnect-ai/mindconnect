package ai.mindconnect.conversation.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A thread in which people and bots write to each other — on the own chat
 * page, or in a channel such as WhatsApp or Teams.
 *
 * @param channel  the channel it happens in, by its name; null for none but this installation
 * @param metadata what the channel or others keep with it, such as the channel's own id of the thread
 */
public record Conversation(
        ConversationId id,
        String topic,
        String channel,
        ConversationStatus status,
        List<Participant> participants,
        Map<String, Object> metadata,
        Instant createdAt,
        Instant updatedAt
) {

    public Conversation {
        participants = participants == null ? List.of() : List.copyOf(participants);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    public Optional<Participant> participant(ParticipantId participant) {
        return participants.stream().filter(p -> p.id().equals(participant)).findFirst();
    }

    public Conversation with(Participant participant, Instant at) {
        List<Participant> changed = new ArrayList<>(participants);
        changed.removeIf(p -> p.id().equals(participant.id()));
        changed.add(participant);
        return new Conversation(id, topic, channel, status, changed, metadata, createdAt, at);
    }

    public Conversation withStatus(ConversationStatus changed, Instant at) {
        return new Conversation(id, topic, channel, changed, participants, metadata, createdAt, at);
    }
}
