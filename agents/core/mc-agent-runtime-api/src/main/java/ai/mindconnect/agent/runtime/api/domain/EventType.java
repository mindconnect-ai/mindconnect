package ai.mindconnect.agent.runtime.api.domain;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

import ai.mindconnect.agent.loop.domain.StreamEvent;
import ai.mindconnect.agent.loop.domain.TurnInterruptionEvent;
import ai.mindconnect.agent.loop.domain.TurnMessageConverter;
import ai.mindconnect.agent.loop.domain.TurnStreamEvent;

/**
 * One kind of {@link SessionEvent}: its name, and how the values of an
 * event are read back as its typed form — the other way, an event writes
 * itself ({@link StreamEvent#toDetails()}). The runtime's own kinds are the
 * constants here; a plugin keeps the constants of its kinds itself —
 * whoever knows the plugin reads its events with them, and nothing has to
 * be registered.
 *
 * @param <T> the typed form of an event of this kind
 */
public record EventType<T extends StreamEvent>(String kind, Function<Map<String, Object>, T> reader) {

    public static final EventType<TurnStreamEvent.AskingLlm> ASKING_LLM = new EventType<>("asking_llm",
            details -> new TurnStreamEvent.AskingLlm());

    public static final EventType<TurnStreamEvent.TextDelta> TEXT_DELTA = new EventType<>("text_delta",
            details -> new TurnStreamEvent.TextDelta(text(details, "text")));

    public static final EventType<TurnStreamEvent.Thinking> THINKING = new EventType<>("thinking",
            details -> new TurnStreamEvent.Thinking(text(details, "text")));

    public static final EventType<TurnStreamEvent.ToolCallStarted> TOOL_CALL_STARTED =
            new EventType<>("tool_call_started", details -> new TurnStreamEvent.ToolCallStarted(
                    text(details, "callId"), text(details, "toolName"), map(details, "arguments"),
                    details.get("index") instanceof Number index ? index.intValue() : 0));

    public static final EventType<TurnStreamEvent.ToolCallResult> TOOL_CALL_RESULT =
            new EventType<>("tool_call_result", details -> new TurnStreamEvent.ToolCallResult(
                    text(details, "callId"), text(details, "toolName"), text(details, "result")));

    public static final EventType<TurnStreamEvent.ToolCallFailed> TOOL_CALL_FAILED =
            new EventType<>("tool_call_failed", details -> new TurnStreamEvent.ToolCallFailed(
                    text(details, "callId"), text(details, "toolName"), text(details, "error")));

    public static final EventType<TurnStreamEvent.MessageWritten> MESSAGE = new EventType<>("message",
            details -> new TurnStreamEvent.MessageWritten(
                    new TurnMessageConverter().fromJson(map(details, "message"))));

    public static final EventType<TurnStreamEvent.TurnUsage> TURN_USAGE = new EventType<>("turn_usage",
            details -> new TurnStreamEvent.TurnUsage(number(details, "inputTokens"), number(details, "outputTokens")));

    public static final EventType<TurnInterruptionEvent.ApprovalRequested> APPROVAL_REQUESTED =
            new EventType<>("approval_requested", details -> new TurnInterruptionEvent.ApprovalRequested(
                    text(details, "requestId"), text(details, "callId"), text(details, "toolName"),
                    map(details, "arguments"),
                    text(details, "originSessionId")));

    public static final EventType<TurnInterruptionEvent.InputRequested> INPUT_REQUESTED =
            new EventType<>("input_requested", details -> new TurnInterruptionEvent.InputRequested(
                    text(details, "requestId"), text(details, "interruptionKind"), text(details, "callId"),
                    text(details, "toolName"), map(details, "details")));

    public static final EventType<SessionStreamEvent.Waiting> WAITING = new EventType<>("waiting",
            details -> new SessionStreamEvent.Waiting());

    public static final EventType<SessionStreamEvent.Resumed> RESUMED = new EventType<>("resumed",
            details -> new SessionStreamEvent.Resumed());

    public static final EventType<SessionStreamEvent.Done> DONE = new EventType<>("done",
            details -> new SessionStreamEvent.Done(status(details), text(details, "text"),
                    text(details, "incompleteReason")));

    public static final EventType<SessionStreamEvent.Error> ERROR = new EventType<>("error",
            details -> new SessionStreamEvent.Error(status(details), text(details, "message")));

    public static final EventType<SessionStreamEvent.SubAgentStarted> SUB_AGENT_STARTED =
            new EventType<>("sub_agent_started",
                    details -> new SessionStreamEvent.SubAgentStarted(text(details, "agentName")));

    public static final EventType<SessionStreamEvent.SubAgentDone> SUB_AGENT_DONE =
            new EventType<>("sub_agent_done", details -> new SessionStreamEvent.SubAgentDone(
                    text(details, "agentName"), text(details, "finalText")));

    /** The runtime's own kinds: the loop's, then what the runtime adds. */
    public static final List<EventType<?>> TYPES = List.of(ASKING_LLM, TEXT_DELTA, THINKING, TOOL_CALL_STARTED,
            TOOL_CALL_RESULT, TOOL_CALL_FAILED, MESSAGE, TURN_USAGE, APPROVAL_REQUESTED, INPUT_REQUESTED, WAITING,
            RESUMED, DONE, ERROR, SUB_AGENT_STARTED, SUB_AGENT_DONE);

    T read(Map<String, Object> details) {
        return reader.apply(details);
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
}
