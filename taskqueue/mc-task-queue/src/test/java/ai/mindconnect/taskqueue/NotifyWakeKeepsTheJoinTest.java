package ai.mindconnect.taskqueue;

import ai.mindconnect.taskqueue.local.LocalTaskQueue;
import ai.mindconnect.taskqueue.memory.InMemoryTaskStore;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A notification wake must not erase the join: the woken parent still sees
 * what it is waiting for, and finishes only when the children really are
 * terminal. This is the deterministic version of the race that made
 * {@code ParentChildNotificationTest} flaky — one child reports early and
 * keeps running while the others have not even reached their work.
 */
class NotifyWakeKeepsTheJoinTest {

    @Test
    void parentWokenByAMessageStillSeesItsOpenChildren() throws Exception {
        var store = new InMemoryTaskStore();
        var queue = new LocalTaskQueue(store);
        var hold = new CountDownLatch(1);          // keeps every child running

        queue.register("child", ctx -> {
            if ("a".equals(ctx.task().payload().get("name"))) {
                ctx.notifyParent(Map.of("child", "a"));   // reports early...
            }
            hold.await();                                 // ...and keeps working
            return TaskOutcome.done("child done");
        });

        List<Set<String>> waitingForSeenOnResume = new CopyOnWriteArrayList<>();
        queue.register("parent", ctx -> {
            if (!ctx.isResumed()) {
                ctx.submitChild("child", Map.of("name", "a"));
                ctx.submitChild("child", Map.of("name", "b"));
                ctx.submitChild("child", Map.of("name", "c"));
                return TaskOutcome.suspendUntilChildren();
            }
            Set<String> open = ctx.task().waitingFor();   // the store keeps score
            waitingForSeenOnResume.add(open);
            return open.isEmpty()
                    ? TaskOutcome.done("all children done")
                    : TaskOutcome.suspendUntil(open);
        });

        String parentId = queue.submit(TaskSubmission.of("parent", Map.of()));

        // The early message wakes the parent while all three children run —
        // and the woken round must still see all three as open.
        waitUntil(() -> !waitingForSeenOnResume.isEmpty());
        assertThat(waitingForSeenOnResume.get(0)).hasSize(3);
        assertThat(queue.get(parentId).orElseThrow().status()).isNotEqualTo(TaskStatus.COMPLETED);

        hold.countDown();
        TaskRecord parent = queue.await(parentId, Duration.ofSeconds(5));

        assertThat(parent.status()).isEqualTo(TaskStatus.COMPLETED);
        assertThat(parent.result()).isEqualTo("all children done");
        assertThat(queue.children(parentId)).allMatch(c -> c.status() == TaskStatus.COMPLETED);
        queue.close();
    }

    private static void waitUntil(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) throw new AssertionError("timed out");
            Thread.sleep(10);
        }
    }
}
