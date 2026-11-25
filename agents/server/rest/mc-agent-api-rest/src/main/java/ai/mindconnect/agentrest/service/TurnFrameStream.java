package ai.mindconnect.agentrest.service;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Supplier;

import ai.mindconnect.agentrest.dto.SessionStreamFrame;
import ai.mindconnect.agentrest.stream.StreamAttachment;
import ai.mindconnect.agentrest.stream.TurnFrame;
import ai.mindconnect.agentrest.stream.TurnFrameTranslator;
import ai.mindconnect.agent.SessionId;
import ai.mindconnect.agent.runtime.api.domain.StreamEvent;
import ai.mindconnect.agent.runtime.api.domain.TurnId;
import ai.mindconnect.agent.runtime.api.port.in.Subscription;
import ai.mindconnect.agent.runtime.engine.service.SessionEventHub;
import ai.mindconnect.agent.sdk.AgentRuntime;
import org.springframework.stereotype.Service;

/**
 * A turn of the runtime as the frames a client of the REST API streams: to
 * the caller that started or continued it, and from the stream the engine
 * keeps of the session to a client that reattaches.
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

    public TurnFrameStream(AgentRuntime runtime) {
        this.runtime = runtime;
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
     * The session's stream from after {@code afterSeq} on: what the engine
     * still keeps of it is replayed, then it goes on live, across turns. The
     * numbers are the engine's, so a client sees them with gaps: not every
     * event is a frame.
     */
    public StreamAttachment attach(SessionId sessionId, long afterSeq, Consumer<SessionStreamFrame> consumer) {
        SessionEventHub events = runtime.service(SessionEventHub.class);
        long firstBuffered = events.earliestBufferedSeq(sessionId);
        long latest = events.lastSeq(sessionId);
        String liveTurnId = runtime.turns().runningTurn(sessionId).map(turn -> turn.id().value()).orElse(null);
        Subscription attached = events.replay(sessionId, afterSeq, numbered -> {
            TurnId turnId = numbered.submittedTurn();
            if (turnId != null) {
                new TurnFrameTranslator(turnId).frame(numbered.event()).ifPresent(frame ->
                        consumer.accept(new SessionStreamFrame(numbered.seq(), turnId.value(), 1, frame)));
            }
        });
        return new StreamAttachment(attached, liveTurnId, firstBuffered, latest);
    }
}
