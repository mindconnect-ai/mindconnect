package ai.mindconnect.agent.runtime.service.turn;

import ai.mindconnect.message.domain.ChatTurnId;
import ai.mindconnect.agent.SessionId;
import ai.mindconnect.agent.runtime.domain.TurnResult;
import ai.mindconnect.agent.runtime.domain.TurnStatus;
import ai.mindconnect.agent.runtime.port.in.ChatTurnHandle;
import java.util.concurrent.CompletableFuture;

/**
 * In-process implementation of {@link ChatTurnHandle}.
 *
 * <p>Wraps the futures of the turn execution and a {@link Runnable}
 * cancel-action supplied by the producer (typically {@code ctx::cancel}).
 * The handle itself is value-like and holds no turn state — it merely
 * forwards observation and cancellation to the producer.
 */
public final class LocalChatTurnHandle implements ChatTurnHandle {

    private final ChatTurnId id;
    private final SessionId sessionId;
    private final CompletableFuture<String> result;
    private final CompletableFuture<TurnResult> outcome;
    private final Runnable cancelAction;

    /** A handle whose turn never waits for an approval: the outcome is the result. */
    public LocalChatTurnHandle(ChatTurnId id, SessionId sessionId,
                               CompletableFuture<String> result,
                               Runnable cancelAction) {
        this(id, sessionId, result, result.thenApply(text -> TurnResult.completed(id, text)), cancelAction);
    }

    public LocalChatTurnHandle(ChatTurnId id, SessionId sessionId,
                               CompletableFuture<String> result,
                               CompletableFuture<TurnResult> outcome,
                               Runnable cancelAction) {
        this.id = id;
        this.sessionId = sessionId;
        this.result = result;
        this.outcome = outcome;
        this.cancelAction = cancelAction;
    }

    @Override
    public ChatTurnId id() { return id; }

    @Override
    public SessionId sessionId() { return sessionId; }

    @Override
    public TurnStatus status() {
        if (result.isDone()) {
            return result.isCompletedExceptionally() ? TurnStatus.FAILED : TurnStatus.COMPLETED;
        }
        if (outcome.isDone() && !outcome.isCompletedExceptionally() && outcome.join().isIncomplete()) {
            return TurnStatus.INCOMPLETE;
        }
        return TurnStatus.RUNNING;
    }

    @Override
    public CompletableFuture<String> result() { return result; }

    @Override
    public CompletableFuture<TurnResult> outcome() { return outcome; }

    @Override
    public boolean cancel() {
        if (result.isDone()) return false;
        cancelAction.run();
        return true;
    }
}
