package ai.mindconnect.agent.runtime.api.domain;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import ai.mindconnect.agent.SessionId;

/**
 * Something that happened in a session, in the one form it has everywhere:
 * on the engine's channel, in a buffer, on the wire. A kind and the values
 * that belong to it — so an event survives being sent, a plugin's event is
 * no different from the runtime's own, and a client that does not know a
 * kind passes it by.
 *
 * <p>Nobody reads the details by hand: {@link #as} gives one kind in its
 * typed form, {@link #known} every kind the runtime has itself
 * ({@link StreamEvent}).
 *
 * @param kind         what happened, e.g. {@code token}; a plugin names its
 *                     own with a dot, e.g. {@code review.started}
 * @param sessionId    the session it happened in — a sub-agent's own for
 *                     what a sub-agent does
 * @param turnId       the turn it belongs to; {@code null} when it belongs
 *                     to the session and no turn
 * @param parentTurnId the turn that started this one; {@code null} for a
 *                     turn that was submitted
 * @param details      what belongs to the kind, in values that survive
 *                     being sent: strings, numbers, booleans, lists and maps
 */
public record SessionEvent(String kind, SessionId sessionId, TurnId turnId, TurnId parentTurnId,
                           Map<String, Object> details) {

    public SessionEvent {
        // Not Map.copyOf: a detail may be absent, and a turn's answer may be empty.
        details = Collections.unmodifiableMap(new LinkedHashMap<>(details));
    }

    public static <T> SessionEvent of(EventType<T> type, SessionId sessionId, TurnId turnId, TurnId parentTurnId,
                                      T payload) {
        return new SessionEvent(type.kind(), sessionId, turnId, parentTurnId, type.write(payload));
    }

    /** Whether the event is of this kind. */
    public boolean is(EventType<?> type) {
        return type.kind().equals(kind);
    }

    /** The event in its typed form; empty when it is of another kind. */
    public <T> Optional<T> as(EventType<T> type) {
        return is(type) ? Optional.of(type.read(details)) : Optional.empty();
    }

    /** The event as one of the runtime's own kinds; {@link StreamEvent.Other} for any other. */
    public StreamEvent known() {
        return StreamEvent.of(this);
    }

    /** Whether the event belongs to a turn another turn started — what a sub-agent does. */
    public boolean ofSubAgent() {
        return parentTurnId != null;
    }
}
