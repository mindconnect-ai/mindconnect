package ai.mindconnect.message.domain;

import ai.mindconnect.agent.AgentId;
import ai.mindconnect.agent.UserId;

import java.time.Instant;

/**
 * Someone taking part in a conversation. {@code refId} is the raw value of
 * the participant's own id — a user's {@code UserId} or an agent's
 * {@code AgentId} value, which {@link #type()} tells apart.
 */
public record Participant(
        ParticipantId id,
        ConversationId conversationId,
        ParticipantType type,
        String refId,
        String displayName,
        Instant joinedAt
) {

    public static Participant user(ConversationId conversationId, UserId userId, String displayName) {
        return new Participant(ParticipantId.random(), conversationId,
                ParticipantType.USER, userId.value(), displayName, Instant.now());
    }

    public static Participant agent(ConversationId conversationId, AgentId agentId, String displayName) {
        return new Participant(ParticipantId.random(), conversationId,
                ParticipantType.AGENT, agentId.value(), displayName, Instant.now());
    }

}
