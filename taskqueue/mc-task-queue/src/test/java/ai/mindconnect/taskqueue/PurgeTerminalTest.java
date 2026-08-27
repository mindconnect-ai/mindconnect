package ai.mindconnect.taskqueue;

import ai.mindconnect.taskqueue.local.LocalTaskQueue;
import ai.mindconnect.taskqueue.memory.InMemoryTaskStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Retention: finished trees can be forgotten, live ones cannot. The unit is
 * the tree, so nothing is ever left pointing at a parent that no longer exists.
 */
class PurgeTerminalTest {

    private InMemoryTaskStore store;
    private LocalTaskQueue queue;

    @BeforeEach
    void setUp() {
        store = new InMemoryTaskStore();
        queue = new LocalTaskQueue(store);
    }

    @AfterEach
    void tearDown() {
        queue.close();
    }

    @Test
    void forgetsAFinishedTreeWholeAndCountsIt() {
        queue.register("child", ctx -> TaskOutcome.done("child done"));
        queue.register("parent", ctx -> {
            if (!ctx.isResumed()) {
                ctx.submitChild("child", Map.of());
                ctx.submitChild("child", Map.of());
                return TaskOutcome.suspendUntilChildren();
            }
            return TaskOutcome.done("parent done");
        });

        String parentId = queue.submit(TaskSubmission.of("parent", Map.of()));
        queue.await(parentId, Duration.ofSeconds(5));

        assertThat(store.purgeTerminal(Instant.now())).isEqualTo(3);   // parent + two children
        assertThat(store.find(parentId)).isEmpty();
        assertThat(store.byParent(parentId)).isEmpty();
    }

    @Test
    void keepsTheWholeFamilyWhileOneMemberStillRuns() throws Exception {
        var release = new CountDownLatch(1);
        queue.register("slow", ctx -> {
            release.await();
            return TaskOutcome.done("finally");
        });
        queue.register("parent", ctx -> {
            if (!ctx.isResumed()) {
                ctx.submitChild("slow", Map.of());
                return TaskOutcome.suspendUntilChildren();
            }
            return TaskOutcome.done("parent done");
        });

        String parentId = queue.submit(TaskSubmission.of("parent", Map.of()));
        String otherId = queue.submit(TaskSubmission.of("slow", Map.of()));
        Thread.sleep(200);                                  // let both reach the latch

        assertThat(store.purgeTerminal(Instant.now())).isZero();
        assertThat(store.find(parentId)).isPresent();

        release.countDown();
        queue.await(parentId, Duration.ofSeconds(5));
        queue.await(otherId, Duration.ofSeconds(5));
        assertThat(store.purgeTerminal(Instant.now())).isEqualTo(3);   // parent + child + the loner
    }

    @Test
    void respectsTheCutoff() {
        queue.register("echo", ctx -> TaskOutcome.done("echo"));
        String id = queue.submit(TaskSubmission.of("echo", Map.of()));
        queue.await(id, Duration.ofSeconds(5));

        assertThat(store.purgeTerminal(Instant.now().minusSeconds(60))).isZero();
        assertThat(store.find(id)).isPresent();
        assertThat(store.purgeTerminal(Instant.now())).isEqualTo(1);
    }
}
