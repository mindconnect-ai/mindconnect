package ai.mindconnect.llm.service;

import ai.mindconnect.common.Cancellation;
import ai.mindconnect.llm.domain.LlmConfig;
import ai.mindconnect.llm.domain.LlmRequest;
import ai.mindconnect.llm.domain.LlmStreamChunk;
import ai.mindconnect.llm.domain.RateLimitConfig;
import ai.mindconnect.llm.port.in.LlmCallListener;
import ai.mindconnect.llm.port.out.LlmGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Provider-agnostic concurrency limiter around any {@link LlmGateway}. Caps the
 * number of in-flight requests <em>per config name</em> via a semaphore, so a
 * fan-out ({@code run_agents} spawning many sub-agents on the same config)
 * cannot exceed a provider's concurrency / rate limit.
 *
 * <p>The limit comes from {@link LlmConfig#rateLimit()}; a {@code null}
 * rate-limit config means unlimited (the call passes straight through). The
 * semaphore is keyed by config <em>name</em> so all callers of the same config
 * — across turns, sub-agents, and tool loops — share one pool of permits,
 * process-wide.
 *
 * <p>Placed <em>outside</em> {@link RetryingLlmGateway} in the decorator chain
 * so one permit covers a whole call including its 429/529 retries — a backing-off
 * request keeps holding its slot, which naturally reduces pressure on the
 * provider rather than freeing a slot for yet another concurrent attempt.
 *
 * <p>Waiting for a permit is cancellation-aware: it polls in short intervals and
 * bails out if the caller cancels, so a throttled request can still be aborted.
 *
 * <p><b>Permit count changes.</b> The permit pool for a config name is created
 * lazily on first use from that config's limit and then cached. If the limit is
 * edited later, the change takes effect after the cached entry is dropped (e.g.
 * a process restart). This keeps the hot path lock-free; live re-sizing is not a
 * requirement for the rate-limit use case.
 */
public class ThrottlingLlmGateway implements LlmGateway {

    private static final Logger log = LoggerFactory.getLogger(ThrottlingLlmGateway.class);

    /** Poll interval while waiting for a permit, so cancellation stays responsive. */
    private static final long ACQUIRE_POLL_MILLIS = 200L;

    private final LlmGateway delegate;
    private final ConcurrentHashMap<String, Semaphore> semaphores = new ConcurrentHashMap<>();

    public ThrottlingLlmGateway(LlmGateway delegate) {
        this.delegate = delegate;
    }

    /** The wrapped gateway (mainly for tests / introspection). */
    public LlmGateway delegate() {
        return delegate;
    }

    @Override
    public void chatStreaming(LlmConfig config, LlmRequest request,
                              Consumer<LlmStreamChunk> handler,
                              Cancellation cancellation,
                              LlmCallListener listener) {
        Semaphore semaphore = semaphoreFor(config);
        if (semaphore == null) {
            // No rate limit configured → pass straight through.
            delegate.chatStreaming(config, request, handler, cancellation, listener);
            return;
        }

        acquire(semaphore, config, cancellation);
        try {
            delegate.chatStreaming(config, request, handler, cancellation, listener);
        } finally {
            semaphore.release();
        }
    }

    /**
     * Returns the shared semaphore for this config's name, or {@code null} when
     * the config has no rate limit. Created once per config name from the limit
     * seen on first use.
     */
    private Semaphore semaphoreFor(LlmConfig config) {
        RateLimitConfig rl = config.rateLimit();
        if (rl == null || rl.maxConcurrentRequests() < 1) return null;
        return semaphores.computeIfAbsent(config.name(), n -> {
            log.info("Throttling LLM config '{}' to {} concurrent request(s)",
                    n, rl.maxConcurrentRequests());
            return new Semaphore(rl.maxConcurrentRequests(), true);
        });
    }

    private void acquire(Semaphore semaphore, LlmConfig config, Cancellation cancellation) {
        boolean waited = false;
        while (true) {
            if (cancellation.isCancelled()) {
                throw new RuntimeException(
                        "LLM call cancelled while waiting for a rate-limit slot (config '"
                        + config.name() + "')");
            }
            try {
                if (semaphore.tryAcquire(ACQUIRE_POLL_MILLIS, TimeUnit.MILLISECONDS)) {
                    if (waited) {
                        log.debug("Acquired rate-limit slot for config '{}' ({} available)",
                                config.name(), semaphore.availablePermits());
                    }
                    return;
                }
                waited = true;
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(
                        "Interrupted while waiting for a rate-limit slot (config '"
                        + config.name() + "')", ie);
            }
        }
    }
}
