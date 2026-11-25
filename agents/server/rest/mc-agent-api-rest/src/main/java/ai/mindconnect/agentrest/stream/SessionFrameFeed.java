package ai.mindconnect.agentrest.stream;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Supplier;

import ai.mindconnect.agent.runtime.api.domain.SessionEvent;
import ai.mindconnect.agent.runtime.api.domain.StreamEvent;
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

    private final SessionFrameChannels sessionFrames;
    private final Supplier<TurnService> turns;
    /** The turns whose frames go to their session's stream, with what follows them. */
    private final Map<TurnId, Subscription> following = new ConcurrentHashMap<>();

    /** @param turns asked on the first event: the runtime is still being put together when this is */
    public SessionFrameFeed(SessionFrameChannels sessionFrames, Supplier<TurnService> turns) {
        this.sessionFrames = sessionFrames;
        this.turns = turns;
    }

    @Override
    public void onEvent(SessionEvent event) {
        // A turn that was submitted begins: its frames, and those of the turns it starts, are its session's.
        boolean begins = event.is(StreamEvent.ASKING_LLM) && !event.ofSubAgent()
                && !event.as(StreamEvent.ASKING_LLM).orElseThrow().resumed();
        if (!begins || following.containsKey(event.turnId())) {
            return;
        }
        TurnId turnId = event.turnId();
        TurnFrameTranslator translator = new TurnFrameTranslator(turnId);
        Consumer<TurnFrame> frames = sessionFrames.publisherFor(event.sessionId().value(), turnId.value());
        // A listener that comes late is told what it missed: nothing the turn said so far is lost.
        following.put(turnId, turns.get().subscribe(turnId, said -> {
            translator.frame(said).ifPresent(frames);
            if (turnId.equals(said.turnId()) && (said.is(StreamEvent.DONE) || said.is(StreamEvent.ERROR))) {
                Subscription over = following.remove(turnId);
                if (over != null) {
                    over.close();
                }
            }
        }));
    }
}
