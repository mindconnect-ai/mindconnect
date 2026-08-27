package ai.mindconnect.agent.service.round;

import ai.mindconnect.message.domain.MessageType;
import ai.mindconnect.message.domain.ParticipantType;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A message a round PRODUCED but nobody persisted yet — everything a
 * {@link ai.mindconnect.message.domain.Message} will carry except what only
 * the store can assign (id, sequence, conversation, timestamps). The round
 * stays pure by returning these; the loop turns them into real messages
 * between rounds (concept 16: save first, then publish).
 *
 * <p>The factories encode the metadata contract of {@link MessageType}: the
 * pairing keys ({@code callId}, {@code callIds}) always land in metadata,
 * never only in the content JSON.
 */
public record TurnMessage(
        MessageType type,
        ParticipantType senderType,
        String content,
        Map<String, Object> metadata
) {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public TurnMessage {
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    /** The agent's (final) answer text. */
    public static TurnMessage assistant(String text) {
        return new TurnMessage(MessageType.CHAT, ParticipantType.AGENT, text, Map.of());
    }

    /**
     * The assistant turn that requested tool calls. {@code contentJson} is the
     * stored shape ({@code {"toolCalls":[{id,name,arguments}], "thinkingBlocks": ...}})
     * exactly as the LLM adapter serialised it; the ids ride in metadata so
     * the {@link ToolCalls} fold can pair without parsing every message.
     */
    public static TurnMessage toolCalls(String contentJson, List<String> callIds) {
        return new TurnMessage(MessageType.TOOL_CALL, ParticipantType.AGENT, contentJson,
                Map.of("callIds", List.copyOf(callIds)));
    }

    /** Marker: {@code callId} is being executed — persisted BEFORE the tool task is submitted. */
    public static TurnMessage dispatched(String callId) {
        return new TurnMessage(MessageType.TOOL_DISPATCHED, ParticipantType.AGENT, "",
                Map.of("callId", callId));
    }

    /** Result of one tool call; {@code failed} lets the model react to errors. */
    public static TurnMessage toolResult(String callId, String toolName, String output, boolean failed) {
        String content = json(Map.of(
                "toolCallId", callId,
                "toolName", toolName == null ? "" : toolName,
                "result", output == null ? "" : output));
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("callId", callId);
        if (toolName != null) metadata.put("toolName", toolName);
        metadata.put("failed", failed);
        return new TurnMessage(MessageType.TOOL_RESULT, ParticipantType.AGENT, content, metadata);
    }

    /** Copy with one metadata entry added — e.g. the measured {@code durationMs}. */
    public TurnMessage with(String key, Object value) {
        Map<String, Object> extended = new LinkedHashMap<>(metadata);
        extended.put(key, value);
        return new TurnMessage(type, senderType, content, extended);
    }

    private static String json(Map<String, Object> value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalArgumentException("Not JSON-serialisable: " + e.getMessage());
        }
    }
}
