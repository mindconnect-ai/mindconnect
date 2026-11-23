package ai.mindconnect.agent.runtime.engine.adapter.store;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import ai.mindconnect.agent.loop.domain.TurnMessage;
import ai.mindconnect.agent.runtime.api.domain.Session;
import ai.mindconnect.agent.runtime.api.port.out.SessionStore;
import ai.mindconnect.agent.runtime.engine.service.TurnMessageConverter;
import ai.mindconnect.message.domain.ChatTurnId;
import ai.mindconnect.message.domain.ConversationId;
import ai.mindconnect.message.domain.Message;
import ai.mindconnect.message.domain.MessageId;
import ai.mindconnect.message.domain.MessageType;
import ai.mindconnect.message.domain.ParticipantType;

/**
 * A {@link TurnMessage} as the {@link Message} the repository keeps, and
 * back. A turn message is what a turn needs of a message; the stored one
 * also says who sent it, when, and where it stands in the conversation.
 *
 * <p>The text goes into the message's content. Everything a turn message
 * holds beyond text — media parts, tool calls — goes into two metadata
 * entries of its own, so it comes back exactly as it was written.
 */
final class SessionMessageMapper {

    private static final String CONTENT = "turn.content";
    private static final String TOOL_CALLS = "turn.toolCalls";
    /** The sender of what an agent wrote in a session that names no agent. */
    private static final String UNNAMED_AGENT = "agent";

    private final TurnMessageConverter turnMessages = new TurnMessageConverter();

    static ConversationId conversationOf(Session session) {
        return ConversationId.of(session.id().value());
    }

    Message message(Session session, TurnMessage written, int sequenceNumber) {
        Map<String, Object> json = turnMessages.toJson(written);
        Map<String, Object> metadata = new LinkedHashMap<>(written.metadata());
        metadata.put(CONTENT, json.get("content"));
        metadata.put(TOOL_CALLS, json.get("toolCalls"));
        boolean fromUser = written.type() == TurnMessage.Type.USER;
        return compressedAsItsMetadataSays(new Message(MessageId.random(), conversationOf(session),
                fromUser ? session.userId().value()
                        : session.agent() == null ? UNNAMED_AGENT : session.agent().name(),
                fromUser ? ParticipantType.USER : ParticipantType.AGENT, null,
                switch (written.type()) {
                    case USER, ASSISTANT -> MessageType.CHAT;
                    case TOOL_CALL -> MessageType.TOOL_CALL;
                    case TOOL_DISPATCHED -> MessageType.TOOL_DISPATCHED;
                    case TOOL_RESULT -> MessageType.TOOL_RESULT;
                },
                written.text(), metadata, sequenceNumber, Instant.now(), false, null, null, null, null,
                written.turnId() == null ? null : ChatTurnId.of(written.turnId()), null, null));
    }

    /** The stored message with other metadata of the turn message; what it holds beyond text stays. */
    Message withTurnMetadata(Message stored, Map<String, Object> turnMetadata) {
        Map<String, Object> metadata = new LinkedHashMap<>(turnMetadata);
        metadata.put(CONTENT, stored.metadata().get(CONTENT));
        metadata.put(TOOL_CALLS, stored.metadata().get(TOOL_CALLS));
        return compressedAsItsMetadataSays(stored.withMetadata(metadata));
    }

    /** The repository has fields of its own for a message shown shortened; the entry moves there. */
    private static Message compressedAsItsMetadataSays(Message message) {
        if (!(message.metadata().get(SessionStore.COMPRESSED) instanceof String shownInstead)) {
            return message;
        }
        Map<String, Object> metadata = new LinkedHashMap<>(message.metadata());
        metadata.remove(SessionStore.COMPRESSED);
        return message.withMetadata(metadata).withCompressed(shownInstead, null);
    }

    /** Empty for a message no turn wrote: a note of the system, an approval of the earlier runtime. */
    Optional<TurnMessage> turnMessage(Message stored) {
        Optional<TurnMessage.Type> type = switch (stored.type()) {
            case CHAT -> Optional.of(stored.senderType() == ParticipantType.USER
                    ? TurnMessage.Type.USER : TurnMessage.Type.ASSISTANT);
            case TOOL_CALL -> Optional.of(TurnMessage.Type.TOOL_CALL);
            case TOOL_DISPATCHED -> Optional.of(TurnMessage.Type.TOOL_DISPATCHED);
            case TOOL_RESULT -> Optional.of(TurnMessage.Type.TOOL_RESULT);
            default -> Optional.empty();
        };
        return type.map(turnType -> {
            Map<String, Object> metadata = new LinkedHashMap<>(stored.metadata());
            Map<String, Object> json = new LinkedHashMap<>();
            json.put("id", stored.id().value());
            json.put("turnId", stored.turnId() == null ? null : stored.turnId().value());
            json.put("type", turnType.name());
            json.put("content", metadata.containsKey(CONTENT) ? metadata.remove(CONTENT) : List.of());
            json.put("toolCalls", metadata.containsKey(TOOL_CALLS) ? metadata.remove(TOOL_CALLS) : List.of());
            if (stored.compressed() && stored.compressedContent() != null) {
                metadata.put(SessionStore.COMPRESSED, stored.compressedContent());
            }
            json.put("metadata", metadata);
            return turnMessages.fromJson(json);
        });
    }
}
