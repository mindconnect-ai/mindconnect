package ai.mindconnect.agent.starter.runtime.bridge;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Supplier;

import ai.mindconnect.agent.SessionId;
import ai.mindconnect.agent.UserId;
import ai.mindconnect.agent.chat.stream.SessionFrame;
import ai.mindconnect.agent.chat.stream.SessionFrameChannels;
import ai.mindconnect.agent.chat.stream.StreamAttachment;
import ai.mindconnect.agent.chat.stream.TurnFrameTranslator;
import ai.mindconnect.agent.chat.stream.UserChannels;
import ai.mindconnect.agent.chat.stream.UserEvent;
import ai.mindconnect.agent.runtime.api.domain.Interruption;
import ai.mindconnect.agent.runtime.api.domain.SessionEvent;
import ai.mindconnect.agent.runtime.api.domain.TurnId;
import ai.mindconnect.agent.runtime.api.domain.TurnStatus;
import ai.mindconnect.agent.runtime.api.port.in.SessionEventListener;
import ai.mindconnect.agent.runtime.api.port.in.Subscription;
import ai.mindconnect.agent.runtime.api.port.in.TurnService;
import ai.mindconnect.agent.runtime.api.port.out.SessionStore;
import ai.mindconnect.channel.Channel;
import ai.mindconnect.message.domain.ChatTurnId;

/**
 * The two streams a client reconnects to — a session's and its user's — fed
 * from every turn of the runtime on the plugin kernel, whoever submitted
 * it: contributed to the runtime, this hears the events of all sessions.
 * Both streams are buffered, so a client that attaches is replayed what it
 * missed.
 *
 * <p>A sub-agent's turn has no stream of its own: what it says reaches the
 * stream of the turn that called it.
 */
final class ChatStreams implements SessionEventListener {

    /** A turn whose frames go to its session's stream. */
    private record Following(UserId user, TurnFrameTranslator frames, Subscription subscription) { }

    private final SessionFrameChannels sessionFrames;
    private final UserChannels users;
    private final Supplier<TurnService> turns;
    private final Supplier<SessionStore> sessions;
    private final Map<TurnId, Following> following = new ConcurrentHashMap<>();

    /** @param turns asked on the first event: the runtime is still being put together when this is */
    ChatStreams(SessionFrameChannels sessionFrames, UserChannels users, Supplier<TurnService> turns,
                Supplier<SessionStore> sessions) {
        this.sessionFrames = sessionFrames;
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
        if (!submitted || following.containsKey(turnId)) {
            return;
        }
        UserId user = sessions.get().find(started.sessionId())
                .map(session -> UserId.of(session.userId().value())).orElseThrow();
        TurnFrameTranslator frames = new TurnFrameTranslator(turnId, turns.get(),
                sessionFrames.publisherFor(started.sessionId().value(), turnId.value()));
        users.publish(user, new UserEvent.TurnStarted(chat(started), ChatTurnId.of(turnId.value())));
        // A listener that comes late is told what it missed: nothing the turn said so far is lost.
        following.put(turnId, new Following(user, frames, turns.get().subscribe(turnId, frames::onEvent)));
    }

    /** The turn has to ask: a frame, and the user's stream names each tool call that waits. */
    private void asked(SessionEvent.TurnInterrupted interrupted) {
        Following turn = following.get(interrupted.turnId());
        if (turn == null) {
            return;
        }
        turn.frames().interrupted(interrupted.interruptions());
        interrupted.interruptions().stream()
                .filter(interruption -> Interruption.TOOL_APPROVAL.equals(interruption.kind()))
                .forEach(interruption -> users.publish(turn.user(), new UserEvent.ApprovalRequested(
                        chat(interrupted), interruption.call().callId(), interruption.call().toolName())));
    }

    private void finished(SessionEvent.TurnFinished finished) {
        Following turn = following.remove(finished.turnId());
        if (turn == null) {
            return;
        }
        turn.subscription().close();
        users.publish(turn.user(), new UserEvent.TurnFinished(chat(finished),
                ChatTurnId.of(finished.turnId().value()),
                finished.status() == TurnStatus.CANCELLED ? UserEvent.TurnOutcome.CANCELLED
                        : finished.status() == TurnStatus.FAILED ? UserEvent.TurnOutcome.FAILED
                        : UserEvent.TurnOutcome.COMPLETED));
    }

    void answered(SessionId chat, UserId user, String callId, boolean approved) {
        users.publish(user, new UserEvent.ApprovalAnswered(chat, callId, approved));
    }

    /** @param liveTurnId the turn underway in the session; {@code null} when it is idle */
    StreamAttachment attach(SessionId chat, long afterSeq, String liveTurnId,
                            Consumer<Channel.Event<SessionFrame>> consumer) {
        String sessionId = chat.value();
        long firstBuffered = sessionFrames.earliestBufferedSeq(sessionId);
        long latest = sessionFrames.lastSeq(sessionId);
        return new StreamAttachment(sessionFrames.subscribe(sessionId, afterSeq, consumer), liveTurnId,
                firstBuffered, latest);
    }

    private static SessionId chat(SessionEvent event) {
        return SessionId.of(event.sessionId().value());
    }
}
