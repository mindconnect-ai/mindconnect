package ai.mindconnect.agent.runtime.store;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import ai.mindconnect.agent.loop.domain.ContentPart;
import ai.mindconnect.agent.loop.domain.TurnMessage;
import ai.mindconnect.agent.runtime.api.domain.Session;
import ai.mindconnect.agent.runtime.api.port.out.SessionStore;
import ai.mindconnect.agent.loop.domain.TurnMessageConverter;
import ai.mindconnect.jackson.JsonMapperFactory;
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
 * <p>It is written the way the message repository has always held a
 * conversation, so whatever reads conversations there — a chat page built
 * from history, an export — reads these too, and a conversation written
 * before this runtime existed can be continued: a tool call is JSON with
 * its {@code toolCalls} and the model's {@code thinkingBlocks}, a tool
 * result JSON with its {@code result}; the ids pairing them are metadata,
 * which a turn message carries under the same keys.
 */
final class SessionMessageMapper {

    /** Metadata of a stored message that says more than text: its media parts, exactly as written. */
    private static final String CONTENT = "turn.content";
    /** Metadata of a turn message: the thinking the model wants handed back with its tool calls. */
    private static final String THINKING_BLOCKS = "llm.thinkingBlocks";
    /** The sender of what an agent wrote in a session that names no agent. */
    private static final String UNNAMED_AGENT = "agent";
    private static final TypeReference<Map<String, Object>> JSON_OBJECT = new TypeReference<>() { };

    private final TurnMessageConverter turnMessages = new TurnMessageConverter();
    private final ObjectMapper json;

    SessionMessageMapper(JsonMapperFactory json) {
        this.json = json.create();
    }

    /**
     * The conversation a session's messages are in: the one its metadata
     * names, else one under the session's own id. A session that continues
     * a conversation begun elsewhere names it.
     */
    static ConversationId conversationOf(Session session) {
        // Only a session at the root continues a conversation begun elsewhere. A sub-agent's session gets
        // its caller's metadata, this key with it, and must still keep its messages out of the caller's history.
        return ConversationId.of(session.parentId() == null
                && session.metadata().get(RepositorySessionStore.CONVERSATION_ID) instanceof String id
                ? id : session.id().value());
    }

    Message message(Session session, TurnMessage written, int sequenceNumber) {
        Map<String, Object> metadata = new LinkedHashMap<>(written.metadata());
        if (written.content().stream().anyMatch(part -> !(part instanceof ContentPart.Text))) {
            metadata.put(CONTENT, turnMessages.toJson(written).get("content"));
        }
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
                content(written), metadata, sequenceNumber, Instant.now(), false, null, null, null, null,
                written.turnId() == null ? null : ChatTurnId.of(written.turnId()), null, null));
    }

    private String content(TurnMessage written) {
        return switch (written.type()) {
            case TOOL_CALL -> {
                Map<String, Object> content = new LinkedHashMap<>();
                content.put("toolCalls", written.toolCalls().stream().map(call -> {
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("id", call.callId());
                    entry.put("name", call.name());
                    entry.put("arguments", call.arguments());
                    return entry;
                }).toList());
                if (written.metadata().containsKey(THINKING_BLOCKS)) {
                    content.put("thinkingBlocks", written.metadata().get(THINKING_BLOCKS));
                }
                yield written(content);
            }
            case TOOL_RESULT -> written(Map.of(
                    "toolCallId", String.valueOf(written.metadata().get("callId")),
                    "toolName", String.valueOf(written.metadata().getOrDefault("toolName", "")),
                    "result", written.text()));
            default -> written.text();
        };
    }

    /** The stored message with other metadata of the turn message; what it holds beyond text stays. */
    Message withTurnMetadata(Message stored, Map<String, Object> turnMetadata) {
        Map<String, Object> metadata = new LinkedHashMap<>(turnMetadata);
        if (stored.metadata().containsKey(CONTENT)) {
            metadata.put(CONTENT, stored.metadata().get(CONTENT));
        }
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
    @SuppressWarnings("unchecked")
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
            if (stored.compressed() && stored.compressedContent() != null) {
                metadata.put(SessionStore.COMPRESSED, stored.compressedContent());
            }
            String turnId = stored.turnId() == null ? null : stored.turnId().value();
            String text = stored.content() == null ? "" : stored.content();
            return switch (turnType) {
                case TOOL_CALL -> {
                    Map<String, Object> content = read(text);
                    // What a message written the earlier way says in its content only reads as it is written now.
                    if (content.containsKey("thinkingBlocks")) {
                        metadata.putIfAbsent(THINKING_BLOCKS, content.get("thinkingBlocks"));
                    }
                    yield new TurnMessage(stored.id().value(), turnId, turnType, List.of(), toolCalls(content),
                            metadata);
                }
                case TOOL_RESULT -> {
                    Map<String, Object> content = read(text);
                    if (content.get("toolCallId") instanceof String callId) {
                        metadata.putIfAbsent("callId", callId);
                    }
                    if (content.get("toolName") instanceof String toolName && !toolName.isEmpty()) {
                        metadata.putIfAbsent("toolName", toolName);
                    }
                    yield new TurnMessage(stored.id().value(), turnId, turnType,
                            List.of(new ContentPart.Text(String.valueOf(content.getOrDefault("result", text)))),
                            List.of(), metadata);
                }
                case TOOL_DISPATCHED -> new TurnMessage(stored.id().value(), turnId, turnType, List.of(), List.of(),
                        metadata);
                case USER, ASSISTANT -> {
                    if (!metadata.containsKey(CONTENT) && stored.parts() != null) {
                        // Written the earlier way, with its media as parts of the repository's own.
                        yield new TurnMessage(stored.id().value(), turnId, turnType,
                                stored.parts().stream().map(SessionMessageMapper::part).toList(), List.of(),
                                metadata);
                    }
                    Map<String, Object> message = new LinkedHashMap<>();
                    message.put("id", stored.id().value());
                    message.put("turnId", turnId);
                    message.put("type", turnType.name());
                    message.put("content", metadata.containsKey(CONTENT)
                            ? metadata.remove(CONTENT) : List.of(Map.of("kind", "text", "text", text)));
                    message.put("toolCalls", List.of());
                    message.put("metadata", metadata);
                    yield turnMessages.fromJson(message);
                }
            };
        });
    }

    private static ContentPart part(ai.mindconnect.message.domain.ContentPart part) {
        return switch (part) {
            case ai.mindconnect.message.domain.ContentPart.Text text -> new ContentPart.Text(text.text());
            case ai.mindconnect.message.domain.ContentPart.Image image -> new ContentPart.Image(
                    new ContentPart.MediaSource.FileId(image.fileId()), ContentPart.Image.Detail.AUTO, image.name());
            case ai.mindconnect.message.domain.ContentPart.File file -> new ContentPart.Document(
                    new ContentPart.MediaSource.FileId(file.fileId()), file.name());
        };
    }

    @SuppressWarnings("unchecked")
    private static List<TurnMessage.ToolCall> toolCalls(Map<String, Object> content) {
        List<TurnMessage.ToolCall> calls = new ArrayList<>();
        if (content.get("toolCalls") instanceof List<?> stored) {
            for (Object entry : stored) {
                Map<String, Object> call = (Map<String, Object>) entry;
                calls.add(new TurnMessage.ToolCall(String.valueOf(call.get("id")), String.valueOf(call.get("name")),
                        call.get("arguments") instanceof Map<?, ?> arguments
                                ? (Map<String, Object>) arguments : Map.of()));
            }
        }
        return calls;
    }

    private String written(Map<String, Object> content) {
        try {
            return json.writeValueAsString(content);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("A message's content cannot be written as JSON", e);
        }
    }

    /** The content as JSON; an empty object for content that is none — a result stored as plain text reads as its text. */
    private Map<String, Object> read(String content) {
        try {
            return json.readValue(content, JSON_OBJECT);
        } catch (JsonProcessingException e) {
            return Map.of();
        }
    }
}
