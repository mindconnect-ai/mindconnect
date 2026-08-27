package ai.mindconnect.common;

import java.util.ArrayList;
import java.util.List;

/**
 * Cooperative-plus-hard cancellation handle for an in-flight operation.
 * <p>
 * Originally the caller passed one to {@code LlmGateway.chatStreaming}: the
 * gateway registered an {@code abortHook} that closes the underlying HTTP call
 * so {@link #cancel()} both flips the flag <em>and</em> tears down the network
 * connection. That's what makes the LLM cancel fast — the provider stops
 * generating tokens (and stops billing) as soon as the socket closes.
 * <p>
 * The same handle is now reused by the agent tool loop: each in-flight tool
 * call registers a hook that interrupts its worker thread, so cancel propagates
 * to bash subprocesses, OkHttp HTTP calls, and the tree-walking file tools.
 * <p>
 * Multiple hooks may register simultaneously; all are invoked on {@link #cancel()}.
 * A registration may be revoked via {@link Registration#unregister()} once its
 * operation completed normally (otherwise the hooks list would grow unbounded).
 * <p>
 * This handle is single-use. Once cancelled, it stays cancelled.
 */
public final class Cancellation {

    private volatile boolean cancelled = false;
    private final List<Runnable> abortHooks = new ArrayList<>();

    /**
     * Registers a hook to run on cancel. If cancel already fired, the hook
     * runs immediately. Returns a handle the caller can use to unregister
     * the hook after its operation completes normally — keeping the hook
     * list bounded across many sequential tool calls on the same context.
     */
    public synchronized Registration registerAbort(Runnable abortHook) {
        if (cancelled) {
            try { abortHook.run(); } catch (Exception ignored) {}
            return () -> {};
        }
        abortHooks.add(abortHook);
        return () -> {
            synchronized (Cancellation.this) {
                abortHooks.remove(abortHook);
            }
        };
    }

    /** Idempotent. Sets the flag and runs every registered abort hook. */
    public synchronized void cancel() {
        if (cancelled) return;
        cancelled = true;
        for (Runnable hook : abortHooks) {
            try { hook.run(); } catch (Exception ignored) {}
        }
        abortHooks.clear();
    }

    public boolean isCancelled() { return cancelled; }

    /** Returns a no-op cancellation; useful for callers that don't need cancel. */
    public static Cancellation none() { return new Cancellation(); }

    /** Returned by {@link #registerAbort(Runnable)} so callers can revoke their hook. */
    @FunctionalInterface
    public interface Registration { void unregister(); }
}
