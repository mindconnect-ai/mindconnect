package ai.mindconnect.taskqueue;

import ai.mindconnect.taskqueue.memory.InMemorySharedStateStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The shared map between tasks. Its whole reason to be a port rather than a
 * {@code Map} is the claim: concurrent workers must not both believe they were
 * first.
 */
class SharedStateStoreTest {

    private SharedStateStore shared;

    @BeforeEach
    void setUp() {
        shared = new InMemorySharedStateStore();
    }

    @Test
    void theFirstClaimWinsAndTheSecondSeesIt() {
        assertThat(shared.putIfAbsent("crawl-1", "https://a", "task-1")).isTrue();
        assertThat(shared.putIfAbsent("crawl-1", "https://a", "task-2")).isFalse();
        assertThat(shared.get("crawl-1", "https://a")).contains("task-1");
    }

    @Test
    void idsDoNotSeeEachOther() {
        shared.putIfAbsent("crawl-1", "https://a", "task-1");
        assertThat(shared.putIfAbsent("crawl-2", "https://a", "task-9")).isTrue();
        assertThat(shared.all("crawl-1")).containsOnlyKeys("https://a");
    }

    @Test
    void exactlyOneOfManyConcurrentClaimsWins() throws Exception {
        int racers = 64;
        var start = new CountDownLatch(1);
        var done = new CountDownLatch(racers);
        var winners = new AtomicInteger();

        for (int i = 0; i < racers; i++) {
            int racer = i;
            Thread.ofVirtual().start(() -> {
                try {
                    start.await();
                    if (shared.putIfAbsent("crawl-1", "https://contested", "task-" + racer)) {
                        winners.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        done.await();

        assertThat(winners.get()).isEqualTo(1);
    }

    @Test
    void clearForgetsTheWorkAndCountsIt() {
        shared.putIfAbsent("crawl-1", "https://a", true);
        shared.put("crawl-1", "https://b", true);

        assertThat(shared.clear("crawl-1")).isEqualTo(2);
        assertThat(shared.all("crawl-1")).isEmpty();
        assertThat(shared.clear("crawl-1")).isZero();
    }
}
