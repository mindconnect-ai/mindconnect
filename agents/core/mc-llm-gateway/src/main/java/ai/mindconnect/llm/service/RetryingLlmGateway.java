package ai.mindconnect.llm.service;

import ai.mindconnect.common.Cancellation;
import ai.mindconnect.llm.domain.LlmConfig;
import ai.mindconnect.llm.domain.LlmRequest;
import ai.mindconnect.llm.domain.LlmStreamChunk;
import ai.mindconnect.llm.domain.LlmTransientException;
import ai.mindconnect.llm.domain.RetryConfig;
import ai.mindconnect.llm.port.in.LlmCallListener;
import ai.mindconnect.llm.port.out.LlmGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Provider-agnostic retry wrapper around any {@link LlmGateway}. Catches
 * {@link LlmTransientException} (HTTP 429 rate-limit / 529 overloaded) and
 * retries the call with backoff, honouring the server's {@code Retry-After}
 * hint when present.
 *
 * <p><b>Streaming safety.</b> A retry is only safe while <em>no</em> chunk has
 * been forwarded to the caller's handler yet — otherwise a partial response
 * would be duplicated. Transient errors are reported by gateways before any
 * content streams, so this holds in practice. The wrapper enforces it with a
 * one-way "committed" latch: once the first chunk passes through, a later
 * {@link LlmTransientException} is rethrown instead of retried.
 *
 * <p>Backoff is exponential from {@link #BASE_BACKOFF_MILLIS}, capped by
 * {@link #MAX_ATTEMPTS}; a positive {@code Retry-After} from the server takes
 * precedence over the computed delay.
 */
public class RetryingLlmGateway implements LlmGateway {

    private static final Logger log = LoggerFactory.getLogger(RetryingLlmGateway.class);

    private final LlmGateway delegate;

    public RetryingLlmGateway(LlmGateway delegate) {
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
        // No retry policy on the config (null) → no retry at all: a single
        // attempt, transient errors propagate immediately. Retry only happens
        // when a config explicitly opts in with an enabled policy.
        RetryConfig policy = config.retry();
        int maxAttempts = (policy != null && policy.enabled()) ? policy.maxAttempts() : 1;

        int attempt = 0;
        while (true) {
            attempt++;
            // Latch: flips the first time we forward a chunk for THIS attempt.
            // Once any attempt has streamed content we must not retry.
            AtomicBoolean committed = new AtomicBoolean(false);
            Consumer<LlmStreamChunk> guarded = chunk -> {
                committed.set(true);
                handler.accept(chunk);
            };

            try {
                delegate.chatStreaming(config, request, guarded, cancellation, listener);
                return; // completed (possibly after streaming) — done
            } catch (LlmTransientException te) {
                if (committed.get() || attempt >= maxAttempts || cancellation.isCancelled()) {
                    throw te;
                }
                long backoff = policy.backoffForAttempt(attempt, te.retryAfterMillis());
                log.warn("LLM transient error HTTP {} for config '{}' (attempt {}/{}); retrying in {} ms",
                        te.status(), config.name(), attempt, maxAttempts, backoff);
                sleep(backoff, te.status());
            }
        }
    }

    private static void sleep(long millis, int status) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new LlmTransientException(status, 0,
                    "Interrupted while backing off after HTTP " + status);
        }
    }
}
