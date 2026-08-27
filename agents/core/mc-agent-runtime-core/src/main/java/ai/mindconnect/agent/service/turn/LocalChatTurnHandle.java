package ai.mindconnect.agent.service.turn;

import ai.mindconnect.agent.domain.TurnStatus;
import ai.mindconnect.agent.port.in.ChatTurnHandle;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * In-process implementation of {@link ChatTurnHandle}.
 *
 * <p>Wraps the {@link CompletableFuture} returned by the turn execution and a
 * {@link Runnable} cancel-action supplied by the producer (typically
 * {@code ctx::cancel}). The handle itself is value-like and holds no turn
 * state — it merely forwards observation and cancellation to the producer.
 *
 * <p>Used by both the runtime adapter ({@code LocalAgentChatClient}) for
 * user-initiated turns and by {@code LocalSubAgentRunner} for sub-agent
 * turns; the handle contract is identical in both cases.
 */
public final class LocalChatTurnHandle implements ChatTurnHandle {

    private final UUID id;
    private final UUID sessionId;
    private final CompletableFuture<String> result;
    private final Runnable cancelAction;

    public LocalChatTurnHandle(UUID id, UUID sessionId,
                               CompletableFuture<String> result,
                               Runnable cancelAction) {
        this.id = id;
        this.sessionId = sessionId;
        this.result = result;
        this.cancelAction = cancelAction;
    }

    @Override
    public UUID id() { return id; }

    @Override
    public UUID sessionId() { return sessionId; }

    @Override
    public TurnStatus status() {
        if (!result.isDone()) return TurnStatus.RUNNING;
        if (result.isCompletedExceptionally()) return TurnStatus.FAILED;
        return TurnStatus.COMPLETED;
    }

    @Override
    public CompletableFuture<String> result() { return result; }

    @Override
    public boolean cancel() {
        if (result.isDone()) return false;
        cancelAction.run();
        return true;
    }
}
