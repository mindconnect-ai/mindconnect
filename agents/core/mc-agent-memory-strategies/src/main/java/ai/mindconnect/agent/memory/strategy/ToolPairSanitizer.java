package ai.mindconnect.agent.memory.strategy;

import ai.mindconnect.message.domain.Message;
import ai.mindconnect.message.domain.MessageType;
import ai.mindconnect.message.domain.ParticipantType;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Repairs the {@code assistant tool_calls} ↔ {@code tool} pairing in a
 * message window before it is sent to the LLM.
 *
 * <p>OpenAI's chat-completions API rejects requests where a {@code tool}
 * message does not appear <em>immediately</em> after the {@code assistant}
 * message containing the matching {@code tool_call_id}:
 *
 * <pre>
 *   HTTP 400 — An assistant message with 'tool_calls' must be followed by
 *   tool messages responding to each 'tool_call_id'.
 * </pre>
 *
 * <p>The persisted conversation can violate this in three ways:
 * <ul>
 *   <li><b>Interleaved user follow-up</b>: when a user sends a new message
 *       after cancelling a tool call, the {@code CHAT} from the user can
 *       land between the {@code TOOL_CALL} (assistant) and its
 *       {@code TOOL_RESULT} due to write ordering.</li>
 *   <li><b>Manually-deleted messages</b>: the admin UI lets the user delete
 *       individual messages, which can sever a pair.</li>
 *   <li><b>Crash-truncated turns</b>: a {@code TOOL_CALL} was persisted but
 *       the run crashed before any {@code TOOL_RESULT}s.</li>
 * </ul>
 *
 * <p>This sanitizer walks the window once and emits a corrected list:
 * <ol>
 *   <li>Every {@code TOOL_RESULT} is pulled out and indexed by
 *       {@code toolCallId}.</li>
 *   <li>The remaining messages are emitted in order; immediately after
 *       every {@code TOOL_CALL} message, we splice in the matching
 *       {@code TOOL_RESULT}s — in declaration order — so the assistant
 *       message and its responses are adjacent.</li>
 *   <li>Any {@code TOOL_CALL} whose matching result is missing gets a
 *       synthetic <b>placeholder</b> {@code TOOL_RESULT} so the LLM still
 *       sees the call/result symmetry. Better than dropping the call —
 *       the model retains the causal trace (it tried something, it was
 *       cancelled, here's what to do next).</li>
 *   <li>{@code TOOL_RESULT}s without any matching {@code TOOL_CALL} in the
 *       window are dropped (true orphans).</li>
 * </ol>
 *
 * <p>The sanitizer is stateless and side-effect-free; the returned list
 * is a fresh {@link ArrayList}. Synthetic placeholder messages are not
 * persisted — they only exist in the in-memory window.
 */
public final class ToolPairSanitizer {

    private static final Logger log = LoggerFactory.getLogger(ToolPairSanitizer.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Message body for synthetic placeholder tool results — visible to the LLM. */
    private static final String PLACEHOLDER_RESULT =
            "[Tool call has no recorded result — cancelled or lost. Treat as a no-op and proceed.]";

    private ToolPairSanitizer() {}

    public static List<Message> sanitize(List<Message> window) {
        if (window == null || window.isEmpty()) return window;

        // Pass 1: index every TOOL_RESULT by its toolCallId so we can splice
        // it back in next to its parent assistant message.
        Map<String, Message> resultByCallId = new LinkedHashMap<>();
        List<Message> nonResults = new ArrayList<>(window.size());
        for (Message m : window) {
            if (m.type() == MessageType.TOOL_RESULT) {
                String callId = extractToolCallId(m);
                if (callId != null) {
                    resultByCallId.put(callId, m);
                } else {
                    log.warn("TOOL_RESULT seq={} has no toolCallId — dropping", m.sequenceNum());
                }
            } else {
                nonResults.add(m);
            }
        }

        // Pass 2: walk non-result messages, emit each, and right after every
        // TOOL_CALL splice in the matching TOOL_RESULTs in call order.
        List<Message> out = new ArrayList<>(window.size());
        Set<String> placedCallIds = new HashSet<>();
        int synthesisedStubs = 0;
        for (Message m : nonResults) {
            out.add(m);
            if (m.type() != MessageType.TOOL_CALL) continue;

            List<String> callIds = extractToolCallIds(m);
            for (String callId : callIds) {
                Message paired = resultByCallId.get(callId);
                if (paired != null) {
                    out.add(paired);
                    placedCallIds.add(callId);
                } else {
                    // No persisted result → synthesise a placeholder.
                    // sequenceNum keeps relative ordering with the parent for
                    // debugging; this message is never persisted.
                    out.add(syntheticPlaceholder(m, callId));
                    placedCallIds.add(callId);
                    synthesisedStubs++;
                }
            }
        }

        int orphanResults = resultByCallId.size() - placedCallIds.size();
        // Only count true orphans (results whose callId was never placed).
        long realOrphans = resultByCallId.keySet().stream()
                .filter(id -> !placedCallIds.contains(id))
                .count();
        if (realOrphans > 0) {
            log.warn("Dropped {} orphan TOOL_RESULT message(s) — no matching TOOL_CALL in window",
                    realOrphans);
        }
        if (synthesisedStubs > 0) {
            log.warn("Synthesised {} placeholder TOOL_RESULT(s) for unmatched tool_call_ids",
                    synthesisedStubs);
        }
        return out;
    }

    /** Reads {@code toolCallId} from a TOOL_RESULT JSON body. */
    private static String extractToolCallId(Message m) {
        try {
            JsonNode node = MAPPER.readTree(m.content());
            String id = node.path("toolCallId").asText("");
            return id.isBlank() ? null : id;
        } catch (Exception e) {
            log.warn("Failed to parse TOOL_RESULT body seq={}: {}", m.sequenceNum(), e.getMessage());
            return null;
        }
    }

    /** Reads the list of {@code toolCalls[].id} from a TOOL_CALL JSON body. */
    private static List<String> extractToolCallIds(Message m) {
        try {
            Map<String, Object> payload = MAPPER.readValue(m.content(), new TypeReference<>() {});
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> calls = (List<Map<String, Object>>) payload.get("toolCalls");
            if (calls == null) return List.of();
            List<String> ids = new ArrayList<>(calls.size());
            for (Map<String, Object> call : calls) {
                Object id = call.get("id");
                if (id != null) ids.add(id.toString());
            }
            return ids;
        } catch (Exception e) {
            log.warn("Failed to parse TOOL_CALL body seq={}: {}", m.sequenceNum(), e.getMessage());
            return List.of();
        }
    }

    /**
     * Synthesises an in-memory TOOL_RESULT for a tool_call_id with no
     * persisted result. Inherits conversation/sender from the parent so the
     * downstream mapper treats it as a normal agent message.
     */
    private static Message syntheticPlaceholder(Message parentToolCall, String callId) {
        String json;
        try {
            json = MAPPER.writeValueAsString(Map.of(
                    "toolCallId", callId,
                    "toolName", "unknown",
                    "result", PLACEHOLDER_RESULT));
        } catch (Exception e) {
            // Hand-built JSON fallback — fields are all controlled strings.
            json = "{\"toolCallId\":\"" + callId
                    + "\",\"toolName\":\"unknown\",\"result\":\"" + PLACEHOLDER_RESULT + "\"}";
        }
        return new Message(
                UUID.randomUUID(),
                parentToolCall.conversationId(),
                parentToolCall.senderId(),
                ParticipantType.AGENT,
                parentToolCall.recipientId(),
                MessageType.TOOL_RESULT,
                json,
                new HashMap<>(),
                parentToolCall.sequenceNum(),   // share seq with parent — never persisted
                Instant.now(),
                false, null, null, null, null,
                parentToolCall.turnId(),
                parentToolCall.run());        // inherit turnId from parent for traceability
    }
}
