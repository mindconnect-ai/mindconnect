package ai.mindconnect.agent.migration.earlier;

import java.math.BigDecimal;
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
import ai.mindconnect.agent.loop.domain.TurnMessageConverter;
import ai.mindconnect.agent.runtime.api.domain.StoredMessage;
import ai.mindconnect.agent.runtime.api.port.out.SessionStore;

/**
 * A message of a conversation as the message repository kept it, read from
 * its JSON and made the turn message a session keeps, with its place and
 * time. Read from the JSON, not through the repository: the message types a
 * tool call and its result were stored as are gone from it.
 *
 * <p>A tool call is JSON with its {@code toolCalls} and the model's
 * {@code thinkingBlocks}, a tool result JSON with its {@code result}; the ids
 * pairing them are metadata. A message no turn wrote — a note of the system,
 * an approval of the earlier runtime — is not read.
 */
final class EarlierMessages {

    /** Metadata of a stored message that says more than text: its media parts, as the runtime wrote them. */
    private static final String CONTENT = "turn.content";
    /** Metadata of a turn message: the thinking the model wants handed back with its tool calls. */
    private static final String THINKING_BLOCKS = "llm.thinkingBlocks";
    private static final TypeReference<Map<String, Object>> OBJECT = new TypeReference<>() { };

    private final TurnMessageConverter turnMessages = new TurnMessageConverter();
    private final ObjectMapper json;

    EarlierMessages(ObjectMapper json) {
        this.json = json;
    }

    /** The messages of a conversation that a turn wrote, in their order. */
    List<StoredMessage> read(List<Map<String, Object>> conversation) {
        return conversation.stream().map(this::read).flatMap(Optional::stream).toList();
    }

    @SuppressWarnings("unchecked")
    private Optional<StoredMessage> read(Map<String, Object> stored) {
        Optional<TurnMessage.Type> type = switch (String.valueOf(stored.get("type"))) {
            case "CHAT" -> Optional.of("USER".equals(stored.get("senderType"))
                    ? TurnMessage.Type.USER : TurnMessage.Type.ASSISTANT);
            case "TOOL_CALL" -> Optional.of(TurnMessage.Type.TOOL_CALL);
            case "TOOL_DISPATCHED" -> Optional.of(TurnMessage.Type.TOOL_DISPATCHED);
            case "TOOL_RESULT" -> Optional.of(TurnMessage.Type.TOOL_RESULT);
            default -> Optional.empty();
        };
        return type.map(turnType -> {
            String id = String.valueOf(stored.get("id"));
            String turnId = stored.get("turnId") instanceof String value ? value : null;
            String text = stored.get("content") instanceof String value ? value : "";
            Map<String, Object> metadata = stored.get("metadata") instanceof Map<?, ?> map
                    ? new LinkedHashMap<>((Map<String, Object>) map) : new LinkedHashMap<>();
            if (Boolean.TRUE.equals(stored.get("compressed")) && stored.get("compressedContent") instanceof String note) {
                metadata.put(SessionStore.COMPRESSED, note);
            }
            TurnMessage message = switch (turnType) {
                case TOOL_CALL -> {
                    Map<String, Object> content = object(text);
                    if (content.containsKey("thinkingBlocks")) {
                        metadata.putIfAbsent(THINKING_BLOCKS, content.get("thinkingBlocks"));
                    }
                    yield new TurnMessage(id, turnId, turnType, List.of(), toolCalls(content), metadata);
                }
                case TOOL_RESULT -> {
                    Map<String, Object> content = object(text);
                    if (content.get("toolCallId") instanceof String callId) {
                        metadata.putIfAbsent("callId", callId);
                    }
                    if (content.get("toolName") instanceof String toolName && !toolName.isEmpty()) {
                        metadata.putIfAbsent("toolName", toolName);
                    }
                    yield new TurnMessage(id, turnId, turnType,
                            List.of(new ContentPart.Text(String.valueOf(content.getOrDefault("result", text)))),
                            List.of(), metadata);
                }
                case TOOL_DISPATCHED -> new TurnMessage(id, turnId, turnType, List.of(), List.of(), metadata);
                case USER, ASSISTANT -> {
                    if (!metadata.containsKey(CONTENT) && stored.get("parts") instanceof List<?> parts) {
                        // Written the earlier way, with its media as parts of the repository's own.
                        yield new TurnMessage(id, turnId, turnType, parts.stream().map(EarlierMessages::part).toList(),
                                List.of(), metadata);
                    }
                    Map<String, Object> written = new LinkedHashMap<>();
                    written.put("id", id);
                    written.put("turnId", turnId);
                    written.put("type", turnType.name());
                    written.put("content", metadata.containsKey(CONTENT)
                            ? metadata.remove(CONTENT) : List.of(Map.of("kind", "text", "text", text)));
                    written.put("toolCalls", List.of());
                    written.put("metadata", metadata);
                    yield turnMessages.fromJson(written);
                }
            };
            long place = stored.get("sequenceNum") instanceof Number number ? number.longValue() : 0;
            return new StoredMessage(message, place, instant(stored.get("sentAt")));
        });
    }

    /** A part as the message repository wrote it: {@code text}, or an {@code image} or {@code file} by its id. */
    private static ContentPart part(Object stored) {
        Map<?, ?> part = (Map<?, ?>) stored;
        String name = part.get("name") instanceof String value ? value : null;
        return switch (String.valueOf(part.get("kind"))) {
            case "image" -> new ContentPart.Image(new ContentPart.MediaSource.FileId(String.valueOf(part.get("fileId"))),
                    ContentPart.Image.Detail.AUTO, name);
            case "file" -> new ContentPart.Document(
                    new ContentPart.MediaSource.FileId(String.valueOf(part.get("fileId"))), name);
            default -> new ContentPart.Text(part.get("text") instanceof String text ? text : "");
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

    /** The content as JSON; an empty object for content that is none — a result stored as plain text. */
    private Map<String, Object> object(String content) {
        try {
            return json.readValue(content, OBJECT);
        } catch (JsonProcessingException e) {
            return Map.of();
        }
    }

    /**
     * A time stamp as the earlier stores wrote it: text in the database, and
     * seconds since the epoch in some files; one that is neither is the epoch.
     */
    static Instant instant(Object value) {
        if (value instanceof Number seconds) {
            BigDecimal exact = new BigDecimal(seconds.toString());
            return Instant.ofEpochSecond(exact.longValue(), exact.remainder(BigDecimal.ONE).movePointRight(9).longValue());
        }
        return value instanceof String text && !text.isBlank() ? Instant.parse(text) : Instant.EPOCH;
    }
}
