package ai.mindconnect.agent.runtime.engine.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import ai.mindconnect.agent.runtime.api.domain.Interruption;
import ai.mindconnect.agent.SessionId;
import ai.mindconnect.agent.runtime.api.domain.ToolCall;
import ai.mindconnect.agent.runtime.api.domain.TurnId;

/**
 * What a turn has decided about its tool calls that no task says: which were
 * refused and why, which are interrupted and wait for an answer, which got
 * their answer, and which sub-agent turn answers which call. It is the part
 * of a turn's progress that is neither in the conversation nor in the task
 * tree — and it is data, kept in the turn task's state, so it is still there
 * when the turn is woken on another worker.
 */
final class CallDecisions {

    private static final String KIND = "kind";
    private static final String INTERRUPTED = "interrupted";
    private static final String ANSWERED = "answered";

    /** Call id to what was decided, in the order decided. */
    private final Map<String, Map<String, Object>> byCallId = new LinkedHashMap<>();

    /** The decisions as a turn task's state holds them; an empty state is no decisions. */
    @SuppressWarnings("unchecked")
    static CallDecisions fromState(Object state) {
        CallDecisions decisions = new CallDecisions();
        if (state instanceof Map<?, ?> stored) {
            stored.forEach((callId, decision) ->
                    decisions.byCallId.put((String) callId, new LinkedHashMap<>((Map<String, Object>) decision)));
        }
        return decisions;
    }

    Map<String, Object> toState() {
        return new LinkedHashMap<>(byCallId);
    }

    boolean knows(String callId) {
        return byCallId.containsKey(callId);
    }

    /** The call cannot go on by itself: it waits for an answer from outside. */
    void interrupt(ToolCall call, String interruptionKind, Map<String, Object> details) {
        Map<String, Object> decision = new LinkedHashMap<>();
        decision.put(KIND, INTERRUPTED);
        decision.put("interruptionKind", interruptionKind);
        decision.put("toolName", call.toolName());
        decision.put("arguments", call.arguments());
        decision.put("details", details);
        byCallId.put(call.callId(), decision);
    }

    boolean isInterrupted(String callId) {
        return decision(callId, INTERRUPTED).isPresent();
    }

    /** The interruption that waits under this id, to act on its answer; then it waits no longer. */
    Optional<Interruption> answered(String callId, TurnId turnId, SessionId sessionId) {
        Optional<Interruption> interruption = decision(callId, INTERRUPTED)
                .map(decision -> interruption(callId, decision, turnId, sessionId));
        interruption.ifPresent(found -> byCallId.remove(callId));
        return interruption;
    }

    /** The answer from outside is the call's result. */
    void resultFromAnswer(String callId, String result) {
        byCallId.put(callId, Map.of(KIND, ANSWERED, "text", result));
    }

    Optional<String> resultFromAnswer(String callId) {
        return decision(callId, ANSWERED).map(decision -> (String) decision.get("text"));
    }

    /** The calls that wait for an answer, as the interruptions the turn comes back with. */
    List<Interruption> interruptions(TurnId turnId, SessionId sessionId) {
        List<Interruption> interruptions = new ArrayList<>();
        byCallId.forEach((callId, decision) -> {
            if (INTERRUPTED.equals(decision.get(KIND))) {
                interruptions.add(interruption(callId, decision, turnId, sessionId));
            }
        });
        return interruptions;
    }

    private Optional<Map<String, Object>> decision(String callId, String kind) {
        return Optional.ofNullable(byCallId.get(callId)).filter(decision -> kind.equals(decision.get(KIND)));
    }

    @SuppressWarnings("unchecked")
    private static Interruption interruption(String callId, Map<String, Object> decision, TurnId turnId,
                                             SessionId sessionId) {
        ToolCall call = new ToolCall(callId, (String) decision.get("toolName"),
                (Map<String, Object>) decision.get("arguments"), turnId, sessionId);
        return new Interruption(callId, turnId, (String) decision.get("interruptionKind"), call,
                (Map<String, Object>) decision.get("details"));
    }
}
