package ai.mindconnect.agent.starter.runtime.bridge;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import ai.mindconnect.agent.SessionId;
import ai.mindconnect.agent.UserId;
import ai.mindconnect.agent.chat.stream.UserChannels;
import ai.mindconnect.agent.chat.stream.UserEvent;
import ai.mindconnect.agent.runtime.api.domain.Interruption;
import ai.mindconnect.agent.runtime.api.domain.SessionEvent;
import ai.mindconnect.agent.runtime.api.domain.TurnId;
import ai.mindconnect.agent.runtime.api.domain.TurnStatus;
import ai.mindconnect.agent.runtime.api.port.in.SessionEventListener;
import ai.mindconnect.agent.runtime.api.port.in.TurnService;
import ai.mindconnect.agent.runtime.api.port.out.SessionStore;
import ai.mindconnect.message.domain.ChatTurnId;

/**
 * Feeds the stream of a user — what a session list or a badge reads — from
 * every turn of the runtime on the plugin kernel, whoever submitted it:
 * contributed to the runtime, this hears the events of all sessions.
 *
 * <p>A sub-agent's turn is not told of: a user's clients follow the turns
 * that were submitted.
 */
final class UserEventFeed implements SessionEventListener {

    private final UserChannels users;
    private final Supplier<TurnService> turns;
    private final Supplier<SessionStore> sessions;
    /** The turns underway that were submitted, and whose they are. */
    private final Map<TurnId, UserId> underway = new ConcurrentHashMap<>();

    /** @param turns asked on the first event: the runtime is still being put together when this is */
    UserEventFeed(UserChannels users, Supplier<TurnService> turns, Supplier<SessionStore> sessions) {
        this.users = users;
        this.turns = turns;
        this.sessions = sessions;
    }

    @Override
    public void onEvent(SessionEvent event) {
        switch (event) {
            case SessionEvent.TurnStarted started -> started(started);
            case SessionEvent.TurnInterrupted interrupted -> asked(interrupted);
            case SessionEvent.TurnFinished finished -> finished(finished);
            default -> { }
        }
    }

    private void started(SessionEvent.TurnStarted started) {
        TurnId turnId = started.turnId();
        boolean submitted = turns.get().find(turnId).map(turn -> turn.parent() == null).orElse(false);
        if (!submitted || underway.containsKey(turnId)) {
            return;
        }
        UserId user = sessions.get().find(started.sessionId())
                .map(session -> UserId.of(session.userId().value())).orElseThrow();
        underway.put(turnId, user);
        users.publish(user, new UserEvent.TurnStarted(chat(started), ChatTurnId.of(turnId.value())));
    }

    /** The turn has to ask: the user's stream names each tool call that waits. */
    private void asked(SessionEvent.TurnInterrupted interrupted) {
        UserId user = underway.get(interrupted.turnId());
        if (user == null) {
            return;
        }
        interrupted.interruptions().stream()
                .filter(interruption -> Interruption.TOOL_APPROVAL.equals(interruption.kind()))
                .forEach(interruption -> users.publish(user, new UserEvent.ApprovalRequested(
                        chat(interrupted), interruption.call().callId(), interruption.call().toolName())));
    }

    private void finished(SessionEvent.TurnFinished finished) {
        UserId user = underway.remove(finished.turnId());
        if (user == null) {
            return;
        }
        users.publish(user, new UserEvent.TurnFinished(chat(finished),
                ChatTurnId.of(finished.turnId().value()),
                finished.status() == TurnStatus.CANCELLED ? UserEvent.TurnOutcome.CANCELLED
                        : finished.status() == TurnStatus.FAILED ? UserEvent.TurnOutcome.FAILED
                        : UserEvent.TurnOutcome.COMPLETED));
    }

    void answered(SessionId chat, UserId user, String callId, boolean approved) {
        users.publish(user, new UserEvent.ApprovalAnswered(chat, callId, approved));
    }

    private static SessionId chat(SessionEvent event) {
        return SessionId.of(event.sessionId().value());
    }
}
