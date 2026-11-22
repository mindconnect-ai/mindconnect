package ai.mindconnect.agent.loop.domain;

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
 * <p>Openness is derived: a {@link TurnMessage.Type#TOOL_CALL} without a
 * {@link TurnMessage.Type#TOOL_RESULT} of the same {@code metadata.callId} is
 * open; a {@link TurnMessage.Type#TOOL_DISPATCHED} for it means it is already
 * running. Approval is not this fold's business: the gate sits in the tool
 * task, which simply takes longer when a human must answer first.
 *
 * <p><b>The fold is episode-local.</b> Feed it {@link #episode(List)}, not the
 * whole history: everything before the user message that opened the current
 * turn is a closed chapter — visible to the model as context, untouchable for
 * the executor. A dangling call from a crashed turn three weeks ago must
 * never run again just because it is technically "open"; a new user message
 * is the statement that the old episode is over.
 */
public record ToolCalls(List<Call> calls) {

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
     * The current episode: the current turn's messages, from the user message
     * that opened it on (inclusive) — a user message the runtime inserted
     * within the turn does not start a new one. No user message yet — the
     * whole list is the episode.
     */
    public static List<TurnMessage> episode(List<TurnMessage> history) {
        int start = -1;
        TurnMessage opener = null;
        for (int i = 0; i < history.size(); i++) {
            if (opensTurn(history.get(i), opener)) {
                opener = history.get(i);
                start = i;
            }
        }
        return start < 0 ? history : history.subList(start, history.size());
    }

    /**
     * A user message opens a turn — unless it carries the id of the turn
     * already open, then it continues that one. A message without a turn id
     * always opens.
     */
    private static boolean opensTurn(TurnMessage message, TurnMessage currentOpener) {
        if (message.type() != TurnMessage.Type.USER) return false;
        if (currentOpener == null || message.turnId() == null || currentOpener.turnId() == null) return true;
        return !message.turnId().equals(currentOpener.turnId());
    }

    public static ToolCalls of(List<TurnMessage> episode) {
        Map<String, TurnMessage.ToolCall> requested = new LinkedHashMap<>();
        Set<String> closed = new HashSet<>();
        Set<String> dispatched = new HashSet<>();

        for (TurnMessage message : episode) {
            switch (message.type()) {
                case TOOL_CALL -> message.toolCalls().forEach(c -> requested.putIfAbsent(c.callId(), c));
                case TOOL_RESULT -> callId(message).ifPresent(closed::add);
                case TOOL_DISPATCHED -> callId(message).ifPresent(dispatched::add);
                default -> { }
            }
        }

        List<Call> result = new ArrayList<>(requested.size());
        for (TurnMessage.ToolCall call : requested.values()) {
            State state = closed.contains(call.callId()) ? State.DONE
                    : dispatched.contains(call.callId()) ? State.RUNNING
                    : State.RUNNABLE;
            result.add(new Call(call.callId(), call.name(), call.arguments(), state));
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

    private static Optional<String> callId(TurnMessage message) {
        return Optional.ofNullable(message.metadata().get("callId")).map(String::valueOf);
    }
}
