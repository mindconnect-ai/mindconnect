package ai.mindconnect.taskqueue;

import ai.mindconnect.taskqueue.local.LocalTaskQueue;
import ai.mindconnect.taskqueue.memory.InMemoryTaskStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Delay, fixed point in time, and retry — all three ride on one field,
 * {@code runAfter}. Driven by a steerable clock so nothing here waits.
 */
class ScheduleAndRetryTest {

    /** A clock the test moves by hand. */
    private static final class TestClock extends Clock {
        private volatile Instant now = Instant.parse("2026-01-01T12:00:00Z");
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
        void advance(Duration by) { now = now.plus(by); }
    }

    private TestClock clock;
    private InMemoryTaskStore store;
    private LocalTaskQueue queue;

    @BeforeEach
    void setUp() {
        clock = new TestClock();
        store = new InMemoryTaskStore(clock);
        queue = new LocalTaskQueue(store);
    }

    @AfterEach
    void tearDown() {
        queue.close();
    }

    @Test
    void aTaskWithRunAfterStaysQueuedUntilItIsDue() {
        AtomicInteger runs = new AtomicInteger();
        queue.register("later", ctx -> {
            runs.incrementAndGet();
            return TaskOutcome.done("ran");
        });

        String id = queue.submit(TaskSubmission.of("later", Map.of())
                .at(clock.instant().plus(Duration.ofMinutes(10))));

        sleepBriefly();                                   // give the dispatcher a chance
        assertThat(queue.get(id).orElseThrow().status()).isEqualTo(TaskStatus.QUEUED);
        assertThat(runs).hasValue(0);

        clock.advance(Duration.ofMinutes(11));            // now it is due
        TaskRecord done = queue.await(id, Duration.ofSeconds(5));
        assertThat(done.status()).isEqualTo(TaskStatus.COMPLETED);
        assertThat(runs).hasValue(1);
    }

    @Test
    void aDueTaskDoesNotOvertakeAnUndueOneOfHigherPriority() {
        List<String> order = new java.util.concurrent.CopyOnWriteArrayList<>();
        queue.register("job", ctx -> {
            order.add((String) ctx.task().payload().get("name"));
            return TaskOutcome.done(null);
        });

        // Higher priority, but not due for an hour — must not block the other.
        queue.submit(TaskSubmission.of("job", Map.of("name", "later"))
                .withPriority(10).at(clock.instant().plus(Duration.ofHours(1))));
        String nowId = queue.submit(TaskSubmission.of("job", Map.of("name", "now")));

        queue.await(nowId, Duration.ofSeconds(5));
        assertThat(order).containsExactly("now");
    }

    @Test
    void theQueueRecordsTheExceptionWithoutTheWorkerDoingAnything() {
        queue.register("boom", ctx -> {
            throw new IllegalArgumentException("payload was rubbish");
        });

        String id = queue.submit(TaskSubmission.of("boom", Map.of()));
        TaskRecord failed = queue.await(id, Duration.ofSeconds(5));

        assertThat(failed.status()).isEqualTo(TaskStatus.FAILED);
        TaskFailure failure = failed.failure();
        assertThat(failure.type()).isEqualTo("java.lang.IllegalArgumentException");
        assertThat(failure.message()).isEqualTo("payload was rubbish");
        assertThat(failure.attempt()).isEqualTo(1);
        // The trace is on the record, not only in some node's log file.
        assertThat(failure.stackTrace()).contains("IllegalArgumentException")
                .contains("theQueueRecordsTheExceptionWithoutTheWorkerDoingAnything");
    }

    @Test
    void maxAttemptsRetriesWithBackoffAndThenGivesUp() {
        AtomicInteger attempts = new AtomicInteger();
        queue.register("flaky", ctx -> {
            attempts.incrementAndGet();
            throw new IllegalStateException("attempt " + ctx.task().attempt());
        });
        queue.withRetryPolicy(RetryPolicy.exponentialBackoff(
                Duration.ofSeconds(10), Duration.ofMinutes(1)));

        String id = queue.submit(TaskSubmission.of("flaky", Map.of()).withMaxAttempts(3));

        // Wait for the RETRY to be persisted, not merely for the worker to have
        // counted — the transition happens after the exception unwinds.
        waitForRetryScheduled(id, 1);
        // Waiting for its second attempt: queued, not failed, and the reason why is readable.
        TaskRecord waiting = queue.get(id).orElseThrow();
        assertThat(waiting.runAfter()).isNotNull();
        assertThat(waiting.failure().message()).isEqualTo("attempt 1");

        clock.advance(Duration.ofSeconds(11));
        waitForRetryScheduled(id, 2);
        clock.advance(Duration.ofMinutes(1));             // second backoff is longer

        TaskRecord failed = queue.await(id, Duration.ofSeconds(5));
        assertThat(failed.status()).isEqualTo(TaskStatus.FAILED);
        assertThat(attempts).hasValue(3);                 // exactly maxAttempts
        assertThat(failed.failure().message()).isEqualTo("attempt 3");
    }

    @Test
    void aRetriedTaskThatSucceedsCompletesNormally() {
        AtomicInteger attempts = new AtomicInteger();
        queue.register("flaky", ctx -> {
            if (attempts.incrementAndGet() == 1) throw new IllegalStateException("first one fails");
            return TaskOutcome.done("second one works");
        });
        queue.withRetryPolicy((task, failure) -> Optional.of(Duration.ofSeconds(5)));

        String id = queue.submit(TaskSubmission.of("flaky", Map.of()).withMaxAttempts(2));
        waitForRetryScheduled(id, 1);
        clock.advance(Duration.ofSeconds(6));

        TaskRecord done = queue.await(id, Duration.ofSeconds(5));
        assertThat(done.status()).isEqualTo(TaskStatus.COMPLETED);
        assertThat(done.result()).isEqualTo("second one works");
        assertThat(done.attempt()).isEqualTo(2);
    }

    @Test
    void withoutMaxAttemptsTheDefaultPolicyFailsOnTheFirstError() {
        AtomicInteger attempts = new AtomicInteger();
        queue.register("once", ctx -> {
            attempts.incrementAndGet();
            throw new IllegalStateException("no retry expected");
        });

        String id = queue.submit(TaskSubmission.of("once", Map.of()));   // maxAttempts = 1
        TaskRecord failed = queue.await(id, Duration.ofSeconds(5));

        assertThat(failed.status()).isEqualTo(TaskStatus.FAILED);
        assertThat(attempts).hasValue(1);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static void sleepBriefly() {
        try {
            Thread.sleep(150);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Waits until the failed attempt has actually been requeued for another try. */
    private void waitForRetryScheduled(String id, int afterAttempt) {
        waitFor(() -> queue.get(id)
                .filter(r -> r.status() == TaskStatus.QUEUED)
                .filter(r -> r.attempt() == afterAttempt)
                .filter(r -> r.runAfter() != null)
                .isPresent());
    }

    private void waitFor(java.util.function.BooleanSupplier condition) {
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) return;
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
        throw new AssertionError("condition not met within 5s");
    }
}
