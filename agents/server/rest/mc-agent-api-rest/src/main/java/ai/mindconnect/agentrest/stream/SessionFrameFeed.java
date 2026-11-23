package ai.mindconnect.agentrest.stream;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import ai.mindconnect.agent.runtime.api.domain.SessionEvent;
import ai.mindconnect.agent.runtime.api.domain.TurnId;
import ai.mindconnect.agent.runtime.api.port.in.SessionEventListener;
import ai.mindconnect.agent.runtime.api.port.in.Subscription;
import ai.mindconnect.agent.runtime.api.port.in.TurnService;

/**
 * Feeds the stream of a session a client reconnects to, from every turn of
 * the runtime, whoever submitted it: contributed to the runtime, this hears
 * the events of all sessions.
 *
 * <p>A sub-agent's turn has no stream of its own: what it says reaches the
 * stream of the turn that called it.
 */
public final class SessionFrameFeed implements SessionEventListener {

    /** A turn whose frames go to its session's stream. */
    private record Following(TurnFrameTranslator frames, Subscription subscription) { }

    private final SessionFrameChannels sessionFrames;
    private final Supplier<TurnService> turns;
    private final Map<TurnId, Following> following = new ConcurrentHashMap<>();

    /** @param turns asked on the first event: the runtime is still being put together when this is */
    public SessionFrameFeed(SessionFrameChannels sessionFrames, Supplier<TurnService> turns) {
        this.sessionFrames = sessionFrames;
        this.turns = turns;
    }

    @Override
    public void onEvent(SessionEvent event) {
        switch (event) {
            case SessionEvent.TurnStarted started -> started(started);
            case SessionEvent.TurnInterrupted interrupted -> {
                Following turn = following.get(interrupted.turnId());
                if (turn != null) {
                    turn.frames().interrupted(interrupted.interruptions());
                }
            }
            case SessionEvent.TurnFinished finished -> {
                Following turn = following.remove(finished.turnId());
                if (turn != null) {
                    turn.subscription().close();
                }
            }
            default -> { }
        }
    }

    private void started(SessionEvent.TurnStarted started) {
        TurnId turnId = started.turnId();
        boolean submitted = turns.get().find(turnId).map(turn -> turn.parent() == null).orElse(false);
        if (!submitted || following.containsKey(turnId)) {
            return;
        }
        TurnFrameTranslator frames = new TurnFrameTranslator(turnId, turns.get(),
                sessionFrames.publisherFor(started.sessionId().value(), turnId.value()));
        // A listener that comes late is told what it missed: nothing the turn said so far is lost.
        following.put(turnId, new Following(frames, turns.get().subscribe(turnId, frames::onEvent)));
    }
}
