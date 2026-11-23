package ai.mindconnect.agent.runtime.engine.service;

import java.util.Optional;

import ai.mindconnect.agent.runtime.api.domain.SessionId;
import ai.mindconnect.agent.runtime.api.domain.TurnId;
import ai.mindconnect.taskqueue.SharedStateStore;

/**
 * Puts the turns of one session in a line. Two turns that ran in a session
 * at once would write into each other's conversation — a question between
 * another turn's tool call and its result — so each turn is told which one
 * it comes after, and waits for that one to end.
 *
 * <p>The line is a chain in the shared state: every turn claims the place
 * behind another with a put-if-absent, which only one can win. That makes
 * it hold across processes without a lock — whoever loses the place walks
 * on to the turn that took it.
 */
final class SessionTurnOrder {

    private static final String FIRST = "first";
    private static final String LAST = "last";

    private final SharedStateStore state;

    SessionTurnOrder(SharedStateStore state) {
        this.state = state;
    }

    /** Lines the turn up in its session; the turn it has to wait for, empty when it is the session's first. */
    Optional<TurnId> enqueue(String namespace, SessionId sessionId, TurnId turnId) {
        String line = "session-turns:" + namespace + ":" + sessionId.value();
        // LAST is only a hint where the end is — it may lag behind, which
        // costs a few steps along the chain and never a wrong place.
        String previous = state.get(line, LAST).map(String::valueOf).orElse(FIRST);
        while (!state.putIfAbsent(line, "after:" + previous, turnId.value())) {
            previous = String.valueOf(state.get(line, "after:" + previous).orElseThrow());
        }
        state.put(line, LAST, turnId.value());
        return FIRST.equals(previous) ? Optional.empty() : Optional.of(TurnId.of(previous));
    }
}
