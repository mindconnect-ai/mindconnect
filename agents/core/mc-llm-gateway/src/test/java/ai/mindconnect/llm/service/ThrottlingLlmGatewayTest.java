package ai.mindconnect.llm.service;

import ai.mindconnect.common.Cancellation;
import ai.mindconnect.llm.domain.LlmConfig;
import ai.mindconnect.llm.domain.LlmProvider;
import ai.mindconnect.llm.domain.LlmRequest;
import ai.mindconnect.llm.domain.LlmStreamChunk;
import ai.mindconnect.llm.domain.RateLimitConfig;
import ai.mindconnect.llm.port.in.LlmCallListener;
import ai.mindconnect.llm.port.out.LlmGateway;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

class ThrottlingLlmGatewayTest {

    private static LlmConfig config(String name, RateLimitConfig rateLimit) {
        return new LlmConfig(UUID.randomUUID(), name, LlmProvider.OPENAI,
                "model", "http://x", "key", 0.7, 4096, Map.of(), null,
                false, null, null, rateLimit, null);
    }

    /** A delegate that records peak concurrency and blocks until released. */
    private static final class BlockingGateway implements LlmGateway {
        final AtomicInteger inFlight = new AtomicInteger();
        final AtomicInteger peak = new AtomicInteger();
        final CountDownLatch release;

        BlockingGateway(CountDownLatch release) { this.release = release; }

        @Override
        public void chatStreaming(LlmConfig config, LlmRequest request,
                                  Consumer<LlmStreamChunk> handler,
                                  Cancellation cancellation, LlmCallListener listener) {
            int now = inFlight.incrementAndGet();
            peak.accumulateAndGet(now, Math::max);
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                inFlight.decrementAndGet();
            }
        }
    }

    @Test
    void capsConcurrencyToConfiguredLimit() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        BlockingGateway delegate = new BlockingGateway(release);
        ThrottlingLlmGateway throttled = new ThrottlingLlmGateway(delegate);
        LlmConfig cfg = config("limited", RateLimitConfig.of(2));
        LlmRequest req = LlmRequest.of("limited", List.of());

        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch started = new CountDownLatch(8);
        for (int i = 0; i < 8; i++) {
            pool.submit(() -> {
                started.countDown();
                throttled.chatStreaming(cfg, req, c -> {}, Cancellation.none(), LlmCallListener.NOOP);
            });
        }
        started.await(2, TimeUnit.SECONDS);
        // Give blocked callers a moment to pile up against the semaphore.
        Thread.sleep(300);

        // Only 2 may be inside the delegate at once, despite 8 callers.
        assertThat(delegate.peak.get()).isLessThanOrEqualTo(2);
        assertThat(delegate.inFlight.get()).isLessThanOrEqualTo(2);

        release.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        assertThat(delegate.peak.get()).isEqualTo(2);
    }

    @Test
    void nullRateLimit_passesThroughUnthrottled() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        BlockingGateway delegate = new BlockingGateway(release);
        ThrottlingLlmGateway throttled = new ThrottlingLlmGateway(delegate);
        LlmConfig cfg = config("unlimited", null);
        LlmRequest req = LlmRequest.of("unlimited", List.of());

        ExecutorService pool = Executors.newFixedThreadPool(5);
        for (int i = 0; i < 5; i++) {
            pool.submit(() -> throttled.chatStreaming(
                    cfg, req, c -> {}, Cancellation.none(), LlmCallListener.NOOP));
        }
        Thread.sleep(300);

        // No throttling → all 5 enter the delegate concurrently.
        assertThat(delegate.inFlight.get()).isEqualTo(5);

        release.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
    }
}
