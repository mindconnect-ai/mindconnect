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
 * Caps the in-flight requests per config name with a semaphore, so a fan-out of
 * sub-agents on one config cannot exceed the provider's concurrency limit; the pool
 * is shared process-wide by every caller of that name. It sits outside
 * {@link RetryingLlmGateway} so one permit covers a call including its retries,
 * which keeps a backing-off request from freeing its slot for yet another attempt.
 * Waiting polls in short intervals so a cancelled caller does not block. The pool is
 * created from the config's limit on first use and cached; an edited limit applies
 * after a restart, which keeps the hot path lock-free.
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
