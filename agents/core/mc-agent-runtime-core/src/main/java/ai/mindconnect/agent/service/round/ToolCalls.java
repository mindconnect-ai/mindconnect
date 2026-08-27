package ai.mindconnect.agent.service.round;

import ai.mindconnect.message.domain.Message;
import ai.mindconnect.message.domain.MessageType;
import ai.mindconnect.message.domain.ParticipantType;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The tool calls of a message list, each with its state — the read model a
 * round decides from. Nothing here is stored: the messages already say it,
 * this type folds them once.
 *
 * <p>Derived openness (concept 16): a {@code TOOL_CALL} without a
 * {@code TOOL_RESULT} of the same {@code metadata.callId} is open; a
 * {@code TOOL_DISPATCHED} for it means it is already running. Approval is
 * NOT this fold's business any more: the gate sits in the tool task, which
 * simply takes longer when a human must answer first (legacy
 * {@code APPROVAL_*} messages in old conversations are ignored).
 *
 * <p><b>The fold is episode-local.</b> Feed it {@link #episode(List)}, not the
 * whole history: everything before the last user CHAT message is a closed
 * chapter — visible to the model as context, untouchable for the executor. A
 * dangling call from a crashed turn three weeks ago must never run again just
 * because it is technically "open"; a new user message is the statement that
 * the old episode is over. The episode still reaches back past an
 * {@code APPROVAL_RESPONSE} to the call it approves, because the response is
 * not a user CHAT.
 *
 * <p>Pairing runs on metadata; only the call DETAILS (name, arguments) are
 * parsed out of the {@code TOOL_CALL} content JSON, with a metadata-less
 * fallback for messages persisted before the keys existed.
 *
 */
public record ToolCalls(List<Call> calls) {

    private static final Logger log = LoggerFactory.getLogger(ToolCalls.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public enum State {
        /** Result exists — this call is finished. */
        DONE,
        /** Dispatched, result not there yet. The executor's move. */
        RUNNING,
        /** May start. */
        RUNNABLE
    }

    /** One call and what has become of it so far. */
    public record Call(String callId, String name, Map<String, Object> arguments, State state) {

        public boolean open() {
            return state != State.DONE;
        }
    }

    /**
     * The current episode: everything from the last user CHAT message on
     * (inclusive). No user message yet — the whole list is the episode.
     */
    public static List<Message> episode(List<Message> history) {
        for (int i = history.size() - 1; i >= 0; i--) {
            Message message = history.get(i);
            if (message.type() == MessageType.CHAT && message.senderType() == ParticipantType.USER) {
                return history.subList(i, history.size());
            }
        }
        return history;
    }

    public static ToolCalls of(List<Message> episode) {
        Map<String, ParsedCall> calls = new LinkedHashMap<>();
        Set<String> closed = new HashSet<>();
        Set<String> dispatched = new HashSet<>();

        for (Message message : episode) {
            switch (message.type()) {
                case TOOL_CALL -> parseCalls(message).forEach(c -> calls.putIfAbsent(c.callId(), c));
                case TOOL_RESULT -> {
                    String callId = callId(message);
                    if (callId != null) closed.add(callId);
                }
                case TOOL_DISPATCHED -> {
                    String callId = callId(message);
                    if (callId != null) dispatched.add(callId);
                }
                default -> { }
            }
        }

        List<Call> result = new ArrayList<>(calls.size());
        for (ParsedCall parsed : calls.values()) {
            State state = closed.contains(parsed.callId()) ? State.DONE
                    : dispatched.contains(parsed.callId()) ? State.RUNNING
                    : State.RUNNABLE;
            result.add(new Call(parsed.callId(), parsed.name(), parsed.arguments(), state));
        }
        return new ToolCalls(List.copyOf(result));
    }

    public List<Call> open() {
        return calls.stream().filter(Call::open).toList();
    }

    public boolean allDone() {
        return open().isEmpty();
    }

    public List<Call> inState(State state) {
        return calls.stream().filter(call -> call.state() == state).toList();
    }

    public Optional<Call> byId(String callId) {
        return calls.stream().filter(call -> call.callId().equals(callId)).findFirst();
    }

    // ── reading messages ────────────────────────────────────────────────────

    private record ParsedCall(String callId, String name, Map<String, Object> arguments) { }

    /** Name and arguments live in the content JSON; only the pairing runs on metadata. */
    private static List<ParsedCall> parseCalls(Message message) {
        try {
            JsonNode root = MAPPER.readTree(message.content());
            JsonNode callsNode = root.path("toolCalls");
            List<ParsedCall> parsed = new ArrayList<>(callsNode.size());
            for (JsonNode call : callsNode) {
                @SuppressWarnings("unchecked")
                Map<String, Object> arguments = call.has("arguments")
                        ? MAPPER.convertValue(call.get("arguments"), Map.class)
                        : Map.of();
                parsed.add(new ParsedCall(call.path("id").asText(), call.path("name").asText(), arguments));
            }
            return parsed;
        } catch (Exception e) {
            // A TOOL_CALL nobody can read must not silently vanish from the
            // fold — it would flip from "open" to "gone" and never be closed.
            log.warn("Unreadable TOOL_CALL content in message {}: {}", message.id(), e.toString());
            return List.of();
        }
    }

    /** {@code metadata.callId}, with a content-JSON fallback for pre-concept-16 messages. */
    private static String callId(Message message) {
        String fromMetadata = string(message.metadata().get("callId"));
        if (fromMetadata != null) return fromMetadata;
        try {
            JsonNode node = MAPPER.readTree(message.content()).path("toolCallId");
            return node.isMissingNode() || node.isNull() ? null : node.asText();
        } catch (Exception e) {
            return null;
        }
    }

    private static String string(Object value) {
        return value == null ? null : String.valueOf(value);
    }

}
