package ai.mindconnect.taskqueue;

import ai.mindconnect.taskqueue.local.LocalTaskQueue;
import ai.mindconnect.taskqueue.memory.InMemoryTaskStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The task tree is part of the API, not of anyone's bookkeeping: a child knows
 * its parent through {@link TaskRecord#parentTaskId()}, a parent reaches its
 * children through {@code children()}, and {@code cancel} walks that same link.
 */
class TaskTreeTest {

    private LocalTaskQueue queue;

    @BeforeEach
    void setUp() {
        queue = new LocalTaskQueue(new InMemoryTaskStore());
    }

    @AfterEach
    void tearDown() {
        queue.close();
    }

    @Test
    void parentAndChildrenKnowEachOtherWithoutStateBookkeeping() {
        CountDownLatch release = new CountDownLatch(1);
        queue.register("child", ctx -> {
            release.await();
            return TaskOutcome.done("done-" + ctx.task().payload().get("name"));
        });
        queue.register("parent", ctx -> {
            if (!ctx.isResumed()) {
                for (String name : List.of("a", "b")) {
                    ctx.submitChild("child", Map.of("name", name));   // link set by the API
                }
                return TaskOutcome.suspendUntilChildren();            // no ids to collect
            }
            return TaskOutcome.done(ctx.children().stream()
                    .map(TaskRecord::result).sorted().collect(Collectors.joining(",")));
        });

        String parentId = queue.submit(TaskSubmission.of("parent", Map.of()));
        waitFor(() -> queue.get(parentId).map(r -> r.status() == TaskStatus.SUSPENDED).orElse(false));

        // Downward: the parent lists its children.
        List<TaskRecord> children = queue.children(parentId);
        assertThat(children).hasSize(2);
        // Upward: each child names its parent.
        assertThat(children).allSatisfy(c -> assertThat(c.parentTaskId()).isEqualTo(parentId));
        // And the queue's waiting set matches the tree.
        assertThat(queue.get(parentId).orElseThrow().waitingFor())
                .containsExactlyInAnyOrderElementsOf(children.stream().map(TaskRecord::id).toList());

        release.countDown();
        TaskRecord done = queue.await(parentId, Duration.ofSeconds(5));
        assertThat(done.status()).isEqualTo(TaskStatus.COMPLETED);
        assertThat(done.result()).isEqualTo("done-a,done-b");
    }

    @Test
    void cancellingAParentCancelsItsRunningChildren() throws Exception {
        CountDownLatch bothStarted = new CountDownLatch(2);
        queue.register("slow-child", ctx -> {
            bothStarted.countDown();
            while (!ctx.cancelRequested()) {
                TimeUnit.MILLISECONDS.sleep(20);
            }
            return TaskOutcome.done("never reached");
        });
        queue.register("parent", ctx -> {
            ctx.submitChild("slow-child", Map.of("name", "a"));
            ctx.submitChild("slow-child", Map.of("name", "b"));
            ctx.updateState(Map.of("phase", "await"));
            return TaskOutcome.suspendUntilChildren();
        });

        String parentId = queue.submit(TaskSubmission.of("parent", Map.of()));
        waitFor(() -> queue.get(parentId).map(r -> r.status() == TaskStatus.SUSPENDED).orElse(false));
        assertThat(bothStarted.await(5, TimeUnit.SECONDS)).isTrue();

        List<String> childIds = queue.children(parentId).stream().map(TaskRecord::id).toList();
        assertThat(childIds).hasSize(2);

        assertThat(queue.cancel(parentId)).isTrue();                  // task-manager kill

        assertThat(queue.await(parentId, Duration.ofSeconds(5)).status()).isEqualTo(TaskStatus.CANCELLED);
        for (String childId : childIds) {
            assertThat(queue.await(childId, Duration.ofSeconds(5)).status())
                    .as("child %s", childId)
                    .isEqualTo(TaskStatus.CANCELLED);
        }
    }

    @Test
    void cancelReachesGrandchildrenAndQueuedChildrenAlike() throws Exception {
        CountDownLatch grandchildStarted = new CountDownLatch(1);
        queue.register("grandchild", ctx -> {
            grandchildStarted.countDown();
            while (!ctx.cancelRequested()) {
                TimeUnit.MILLISECONDS.sleep(20);
            }
            return TaskOutcome.done("never reached");
        });
        queue.register("child", ctx -> {
            ctx.submitChild("grandchild", Map.of());
            ctx.updateState(Map.of("phase", "await"));
            return TaskOutcome.suspendUntilChildren();
        });
        queue.register("parent", ctx -> {
            ctx.submitChild("child", Map.of());
            // A second child nobody will ever run: no worker is registered for
            // its type, so it stays QUEUED — and must still be cancelled.
            ctx.submitChild("never-runs", Map.of());
            ctx.updateState(Map.of("phase", "await"));
            return TaskOutcome.suspendUntilChildren();
        });

        String parentId = queue.submit(TaskSubmission.of("parent", Map.of()));
        assertThat(grandchildStarted.await(5, TimeUnit.SECONDS)).isTrue();

        String childId = queue.children(parentId).stream()
                .filter(c -> c.type().equals("child")).findFirst().orElseThrow().id();
        String queuedId = queue.children(parentId).stream()
                .filter(c -> c.type().equals("never-runs")).findFirst().orElseThrow().id();
        String grandchildId = queue.children(childId).get(0).id();
        assertThat(queue.get(queuedId).orElseThrow().status()).isEqualTo(TaskStatus.QUEUED);

        assertThat(queue.cancel(parentId)).isTrue();

        // Three levels deep plus the never-started sibling — all CANCELLED.
        for (String id : List.of(parentId, childId, queuedId, grandchildId)) {
            assertThat(queue.await(id, Duration.ofSeconds(5)).status())
                    .as("task %s", id)
                    .isEqualTo(TaskStatus.CANCELLED);
        }
    }

    @Test
    void isResumedIsFalseOnTheFirstDeliveryAndTrueForEveryContinuation() {
        queue.register("child", ctx -> TaskOutcome.done("child"));

        List<Boolean> resumedFlags = new java.util.concurrent.CopyOnWriteArrayList<>();
        queue.register("parent", ctx -> {
            resumedFlags.add(ctx.isResumed());
            if (!ctx.isResumed()) {
                // Nothing to read yet: no continuation, no messages.
                assertThat(ctx.task().state()).isEmpty();
                assertThat(ctx.notifications()).isEmpty();
                ctx.submitChild("child", Map.of());
                ctx.updateState(Map.of("round", 1));
                return TaskOutcome.suspendUntilChildren();
            }
            // The continuation is what the PREVIOUS round left behind —
            // round 1 on the first wake, round 2 on the second.
            assertThat(ctx.task().state()).containsKey("round");
            int round = (int) ctx.task().state().get("round");
            if (round == 1) {                           // park once more, deliberately
                ctx.updateState(Map.of("round", 2));
                return TaskOutcome.suspendUntilNotified();
            }
            return TaskOutcome.done("rounds: " + round);
        });

        String parentId = queue.submit(TaskSubmission.of("parent", Map.of()));
        waitFor(() -> queue.get(parentId).map(r -> r.status() == TaskStatus.SUSPENDED
                && r.waitingFor().isEmpty()).orElse(false));
        queue.notify(parentId, TaskNotification.from(null, Map.of()));

        TaskRecord done = queue.await(parentId, Duration.ofSeconds(5));
        assertThat(done.status()).isEqualTo(TaskStatus.COMPLETED);
        // False exactly once — the first delivery — then true for every round.
        assertThat(resumedFlags).containsExactly(false, true, true);
    }

    private void waitFor(java.util.function.BooleanSupplier condition) {
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) return;
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
        throw new AssertionError("condition not met within 5s");
    }
}
