package ai.mindconnect.agent.runtime.api.domain;

import java.util.Collections;
import ai.mindconnect.agent.loop.domain.TurnStreamEvent;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import ai.mindconnect.agent.SessionId;
import ai.mindconnect.agent.loop.domain.StreamEvent;

/**
 * Something that happened in a session, in the one form it has everywhere:
 * on the engine's channel, in a buffer, on the wire. A kind and the values
 * that belong to it — so an event survives being sent, a plugin's event is
 * no different from the runtime's own, and a client that does not know a
 * kind passes it by.
 *
 * <p>Nobody reads the details by hand: {@link #as} gives one kind in its
 * typed form, {@link #known} every kind the runtime has itself: what the
 * loop reports of a turn ({@code TurnStreamEvent}) and what the runtime adds
 * ({@link SessionStreamEvent}).
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

    /** An event from its typed form: the kind and the values are the ones the form names itself. */
    public static SessionEvent of(SessionId sessionId, TurnId turnId, TurnId parentTurnId, StreamEvent event) {
        return new SessionEvent(event.kind(), sessionId, turnId, parentTurnId, event.toDetails());
    }

    /** Whether the event is of this kind. */
    public boolean is(EventType<?> type) {
        return type.kind().equals(kind);
    }

    /** The event in its typed form; empty when it is of another kind. */
    public <T extends StreamEvent> Optional<T> as(EventType<T> type) {
        return is(type) ? Optional.of(type.read(details)) : Optional.empty();
    }

    /** The event as one of the runtime's own kinds; {@link SessionStreamEvent.Other} for any other. */
    public StreamEvent known() {
        for (EventType<?> type : EventType.TYPES) {
            if (is(type)) {
                return type.read(details);
            }
        }
        return new SessionStreamEvent.Other(this);
    }

    /** Whether the event belongs to a turn another turn started — what a sub-agent does. */
    public boolean ofSubAgent() {
        return parentTurnId != null;
    }
}
