package ai.mindconnect.agentrest.service;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Supplier;

import ai.mindconnect.agentrest.stream.SessionFrame;
import ai.mindconnect.agentrest.stream.SessionFrameChannels;
import ai.mindconnect.agentrest.stream.StreamAttachment;
import ai.mindconnect.agentrest.stream.TurnFrame;
import ai.mindconnect.agentrest.stream.TurnFrameTranslator;
import ai.mindconnect.agent.SessionId;
import ai.mindconnect.agent.runtime.api.domain.StreamEvent;
import ai.mindconnect.agent.runtime.api.domain.TurnId;
import ai.mindconnect.agent.runtime.api.port.in.Subscription;
import ai.mindconnect.agent.sdk.AgentRuntime;
import ai.mindconnect.channel.Channel;
import org.springframework.stereotype.Service;

/**
 * A turn of the runtime as the frames a client of the REST API streams: to
 * the caller that started or continued it, and from the session's buffered
 * stream to a client that reattaches.
 */
@Service
public class TurnFrameStream {

    /** Where the stream of one turn goes. */
    public interface Sink {

        void frame(TurnFrame frame);

        /** The turn has to ask before it can go on. */
        void asked(TurnId turnId);

        /** @param error why the turn ended without the loop ending it; {@code null} for every other end of the stream */
        void ended(String error);
    }

    private final AgentRuntime runtime;
    private final SessionFrameChannels sessionFrames;

    public TurnFrameStream(AgentRuntime runtime, SessionFrameChannels sessionFrames) {
        this.runtime = runtime;
        this.sessionFrames = sessionFrames;
    }

    /**
     * Streams the turn that {@code start} submits or continues, to its end.
     *
     * @param start      submits or resumes the turn; empty when there was nothing to continue
     * @param untilAsked whether the stream ends when the turn has to ask — for a caller that is answered
     *                   with a stream of its own for every answer it gives
     * @return whether a turn was started
     */
    public boolean stream(SessionId sessionId, Supplier<Optional<TurnId>> start, boolean untilAsked, Sink sink) {
        CompletableFuture<TurnId> turn = new CompletableFuture<>();
        CompletableFuture<Void> over = new CompletableFuture<>();
        // Followed before the turn goes on, and from now: a turn that is continued has said things
        // before, and those were streamed to whoever asked then.
        Subscription following = runtime.sessions().stream(sessionId, true, event -> {
            TurnId turnId = turn.join();
            if (over.isDone() || turnId == null) {
                return;
            }
            new TurnFrameTranslator(turnId).frame(event).ifPresent(sink::frame);
            if (event.is(StreamEvent.WAITING)) {
                sink.asked(event.turnId());
                if (untilAsked && over.complete(null)) {
                    sink.ended(null);
                }
            } else if (turnId.equals(event.turnId()) && event.is(StreamEvent.DONE)) {
                if (over.complete(null)) {
                    sink.ended(null);
                }
            } else if (turnId.equals(event.turnId()) && event.is(StreamEvent.ERROR)) {
                if (over.complete(null)) {
                    sink.ended(event.as(StreamEvent.ERROR).orElseThrow().message());
                }
            }
        });
        over.whenComplete((done, failure) -> following.close());
        Optional<TurnId> started;
        try {
            started = start.get();
        } catch (RuntimeException e) {
            turn.complete(null);
            over.complete(null);
            throw e;
        }
        turn.complete(started.orElse(null));
        if (started.isEmpty()) {
            over.complete(null);
        }
        return started.isPresent();
    }

    /**
     * The session's stream from after {@code afterSeq} on: what was buffered
     * since is replayed, then it goes on live, across turns.
     */
    public StreamAttachment attach(SessionId sessionId, long afterSeq, Consumer<Channel.Event<SessionFrame>> consumer) {
        String id = sessionId.value();
        long firstBuffered = sessionFrames.earliestBufferedSeq(id);
        long latest = sessionFrames.lastSeq(id);
        String liveTurnId = runtime.turns().runningTurn(sessionId).map(turn -> turn.id().value()).orElse(null);
        return new StreamAttachment(sessionFrames.subscribe(id, afterSeq, consumer), liveTurnId, firstBuffered,
                latest);
    }
}
