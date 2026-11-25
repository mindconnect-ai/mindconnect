package ai.mindconnect.agent.runtime.api.domain;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import ai.mindconnect.agent.SessionId;
import ai.mindconnect.agent.loop.domain.TurnMessage;
import ai.mindconnect.agent.loop.domain.TurnMessageConverter;

/**
 * The kinds of event the runtime itself tells of a turn, in their typed
 * form — what a client of a session draws: the answer as it is written, the
 * tool calls, a question the turn stops for, the end. The names are those a
 * client of the REST API reads.
 *
 * <p>A closed list, so a switch over it is known to be complete. A kind the
 * runtime does not know — a plugin's — is an {@link Other}.
 */
public sealed interface StreamEvent {

    /** The model is asked: the turn begins, or goes on after it waited. */
    record AskingLlm(boolean resumed) implements StreamEvent { }

    /** A piece of the answer. */
    record Token(String text) implements StreamEvent { }

    /** A piece of the model's reasoning; for showing, never fed back to the model. */
    record Thinking(String text) implements StreamEvent { }

    /**
     * A tool call the model asked for.
     *
     * @param index where the call stands in the model's request, from 0: a
     *              request may ask for several calls at once
     */
    record ToolCallStarted(String callId, String toolName, Map<String, Object> arguments, int index)
            implements StreamEvent { }

    record ToolCallResult(String callId, String toolName, String result) implements StreamEvent { }

    /** A tool call went wrong; the model was told {@code error} and decides. */
    record ToolCallFailed(String callId, String toolName, String error) implements StreamEvent { }

    /**
     * A tool call waits for somebody's approval; the turn stands still until
     * it is resumed with an answer to {@code requestId}.
     *
     * @param originSessionId the session the call was made in — a sub-agent's, when one asks
     */
    record ApprovalRequested(String requestId, String callId, String toolName, Map<String, Object> arguments,
                             SessionId originSessionId) implements StreamEvent { }

    /**
     * The turn stops to ask something that is no tool approval — a question
     * to the user, or what a tool of its own kind asks.
     */
    record InputRequested(String requestId, String interruptionKind, String callId, String toolName,
                          Map<String, Object> details) implements StreamEvent { }

    /**
     * The turn has asked everything it has to ask and stands still: told
     * once after the requests of one stop, however many they are.
     */
    record Waiting() implements StreamEvent { }

    /** What the turn has cost so far, as the providers reported it. */
    record TurnUsage(long inputTokens, long outputTokens) implements StreamEvent { }

    /**
     * The turn came to its end.
     *
     * @param text             the answer; empty for a turn that ended without one
     * @param incompleteReason why a turn that ended {@link TurnStatus#INCOMPLETE} did, as the loop
     *                         names it ({@code MAX_ROUNDS}, {@code MAX_OUTPUT_TOKENS}); {@code null} otherwise
     */
    record Done(TurnStatus status, String text, String incompleteReason) implements StreamEvent { }

    /** The turn broke off: it failed, or was cancelled before it could end itself. */
    record Error(TurnStatus status, String message) implements StreamEvent { }

    /** A turn another turn started has begun: an agent that was called. */
    record SubAgentStarted(String agentName) implements StreamEvent { }

    /** A turn another turn started is over. */
    record SubAgentDone(String agentName, String finalText) implements StreamEvent { }

    /** A whole message is in the conversation — what the user said, what the agent answered, a tool's result. */
    record MessageWritten(TurnMessage message) implements StreamEvent { }

    /** An event of a kind the runtime does not know. */
    record Other(SessionEvent event) implements StreamEvent { }

    EventType<AskingLlm> ASKING_LLM = new EventType<>("asking_llm",
            details -> new AskingLlm(Boolean.TRUE.equals(details.get("resumed"))),
            event -> Map.of("resumed", event.resumed()));

    EventType<Token> TOKEN = new EventType<>("token",
            details -> new Token(text(details, "text")), event -> Map.of("text", event.text()));

    EventType<Thinking> THINKING = new EventType<>("thinking",
            details -> new Thinking(text(details, "text")), event -> Map.of("text", event.text()));

    EventType<ToolCallStarted> TOOL_CALL_STARTED = new EventType<>("tool_call_started",
            details -> new ToolCallStarted(text(details, "callId"), text(details, "toolName"),
                    map(details, "arguments"), details.get("index") instanceof Number index ? index.intValue() : 0),
            event -> values("callId", event.callId(), "toolName", event.toolName(),
                    "arguments", event.arguments(), "index", event.index()));

    EventType<ToolCallResult> TOOL_CALL_RESULT = new EventType<>("tool_call_result",
            details -> new ToolCallResult(text(details, "callId"), text(details, "toolName"), text(details, "result")),
            event -> values("callId", event.callId(), "toolName", event.toolName(), "result", event.result()));

    EventType<ToolCallFailed> TOOL_CALL_FAILED = new EventType<>("tool_call_failed",
            details -> new ToolCallFailed(text(details, "callId"), text(details, "toolName"), text(details, "error")),
            event -> values("callId", event.callId(), "toolName", event.toolName(), "error", event.error()));

    EventType<ApprovalRequested> APPROVAL_REQUESTED = new EventType<>("approval_requested",
            details -> new ApprovalRequested(text(details, "requestId"), text(details, "callId"),
                    text(details, "toolName"), map(details, "arguments"),
                    details.get("originSessionId") instanceof String id ? SessionId.of(id) : null),
            event -> values("requestId", event.requestId(), "callId", event.callId(),
                    "toolName", event.toolName(), "arguments", event.arguments(),
                    "originSessionId", event.originSessionId() == null ? null : event.originSessionId().value()));

    EventType<InputRequested> INPUT_REQUESTED = new EventType<>("input_requested",
            details -> new InputRequested(text(details, "requestId"), text(details, "interruptionKind"),
                    text(details, "callId"), text(details, "toolName"), map(details, "details")),
            event -> values("requestId", event.requestId(), "interruptionKind", event.interruptionKind(),
                    "callId", event.callId(), "toolName", event.toolName(), "details", event.details()));

    EventType<Waiting> WAITING = new EventType<>("waiting", details -> new Waiting(), event -> Map.of());

    EventType<TurnUsage> TURN_USAGE = new EventType<>("turn_usage",
            details -> new TurnUsage(number(details, "inputTokens"), number(details, "outputTokens")),
            event -> Map.of("inputTokens", event.inputTokens(), "outputTokens", event.outputTokens()));

    EventType<Done> DONE = new EventType<>("done",
            details -> new Done(status(details), text(details, "text"), text(details, "incompleteReason")),
            event -> values("status", event.status().name(), "text", event.text(),
                    "incompleteReason", event.incompleteReason()));

    EventType<Error> ERROR = new EventType<>("error",
            details -> new Error(status(details), text(details, "message")),
            event -> values("status", event.status().name(), "message", event.message()));

    EventType<SubAgentStarted> SUB_AGENT_STARTED = new EventType<>("sub_agent_started",
            details -> new SubAgentStarted(text(details, "agentName")),
            event -> values("agentName", event.agentName()));

    EventType<SubAgentDone> SUB_AGENT_DONE = new EventType<>("sub_agent_done",
            details -> new SubAgentDone(text(details, "agentName"), text(details, "finalText")),
            event -> values("agentName", event.agentName(), "finalText", event.finalText()));

    EventType<MessageWritten> MESSAGE = new EventType<>("message",
            details -> new MessageWritten(new TurnMessageConverter().fromJson(map(details, "message"))),
            event -> Map.of("message", new TurnMessageConverter().toJson(event.message())));

    /** The runtime's own kinds, by their name. */
    List<EventType<? extends StreamEvent>> TYPES = List.of(ASKING_LLM, TOKEN, THINKING, TOOL_CALL_STARTED,
            TOOL_CALL_RESULT, TOOL_CALL_FAILED, APPROVAL_REQUESTED, INPUT_REQUESTED, WAITING, TURN_USAGE, DONE, ERROR,
            SUB_AGENT_STARTED, SUB_AGENT_DONE, MESSAGE);

    static StreamEvent of(SessionEvent event) {
        for (EventType<? extends StreamEvent> type : TYPES) {
            if (event.is(type)) {
                return type.read(event.details());
            }
        }
        return new Other(event);
    }

    private static String text(Map<String, Object> details, String key) {
        return details.get(key) instanceof String text ? text : null;
    }

    private static long number(Map<String, Object> details, String key) {
        return details.get(key) instanceof Number number ? number.longValue() : 0;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Map<String, Object> details, String key) {
        return details.get(key) instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private static TurnStatus status(Map<String, Object> details) {
        return details.get("status") instanceof String status ? TurnStatus.valueOf(status) : TurnStatus.COMPLETED;
    }

    /** A map of the pairs given, in their order; a value may be absent. */
    private static Map<String, Object> values(Object... pairs) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            values.put((String) pairs[i], pairs[i + 1]);
        }
        return values;
    }
}
