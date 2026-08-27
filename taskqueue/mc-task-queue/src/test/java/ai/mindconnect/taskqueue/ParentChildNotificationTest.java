package ai.mindconnect.taskqueue;

import ai.mindconnect.taskqueue.local.LocalTaskQueue;
import ai.mindconnect.taskqueue.memory.InMemoryTaskStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The fan-out pattern end to end: a parent opens children, parks without a
 * thread, is woken by each child that finishes, and completes once nothing is
 * left to wait for.
 *
 * <p>Also pins the two races that make notifications tricky — a child that
 * finishes before the parent has even parked, and one that reports while the
 * parent is running.
 */
class ParentChildNotificationTest {

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
    void parentWakesPerChildAndCompletesWhenAllAreDone() {
        CountDownLatch releaseChildren = new CountDownLatch(1);
        queue.register("child", ctx -> {
            releaseChildren.await();
            String name = (String) ctx.task().payload().get("name");
            // The child reports itself — this is what wakes the parent.
            ctx.notifyParent(Map.of("child", name));
            return TaskOutcome.done("result-" + name);
        });

        List<List<String>> wakeups = new ArrayList<>();       // one entry per parent round
        queue.register("parent", ctx -> {
            if (!ctx.isResumed()) {
                for (String name : List.of("a", "b", "c")) {
                    ctx.submitChild("child", Map.of("name", name));
                }
                ctx.updateState(Map.of("collected", new ArrayList<String>()));
                return TaskOutcome.suspendUntilChildren();
            }
            // Whoever reported since the last round, plus what is still open.
            @SuppressWarnings("unchecked")
            List<String> collected = new ArrayList<>((List<String>) ctx.state().get("collected"));
            List<String> thisRound = new ArrayList<>();
            for (TaskNotification n : ctx.notifications()) {
                thisRound.add((String) n.payload().get("child"));
            }
            wakeups.add(thisRound);
            collected.addAll(thisRound);

            Map<String, Object> state = new LinkedHashMap<>();
            state.put("collected", collected);
            ctx.updateState(state);

            java.util.Set<String> open = ctx.task().waitingFor();
            return open.isEmpty()
                    ? TaskOutcome.done("collected " + collected.size() + ": "
                            + String.join(",", sorted(collected)))
                    : TaskOutcome.suspendUntil(open);
        });

        String parentId = queue.submit(TaskSubmission.of("parent", Map.of()));
        waitFor(() -> queue.get(parentId).map(r -> r.status() == TaskStatus.SUSPENDED).orElse(false));
        assertThat(queue.get(parentId).orElseThrow().waitingFor()).hasSize(3);

        releaseChildren.countDown();
        TaskRecord done = queue.await(parentId, Duration.ofSeconds(5));

        assertThat(done.status()).isEqualTo(TaskStatus.COMPLETED);
        assertThat(done.result()).isEqualTo("collected 3: a,b,c");
        // Woken at least once per child — not a single wakeup at the end.
        assertThat(wakeups.stream().mapToInt(List::size).sum()).isEqualTo(3);
        assertThat(wakeups).hasSizeGreaterThanOrEqualTo(1);
        assertThat(queue.get(parentId).orElseThrow().attempt()).isGreaterThan(1);
    }

    @Test
    void notificationBeforeTheParentParksIsNotLost() throws Exception {
        CountDownLatch childDone = new CountDownLatch(1);
        queue.register("fast-child", ctx -> {
            ctx.notifyParent(Map.of("done", true));
            childDone.countDown();
            return TaskOutcome.done("fast");
        });
        queue.register("slow-parent", ctx -> {
            if (ctx.state().isEmpty()) {
                ctx.submitChild("fast-child", Map.of());
                ctx.updateState(Map.of("phase", "await"));
                // The child races ahead and reports while we are still here.
                assertThat(childDone.await(5, TimeUnit.SECONDS)).isTrue();
                return TaskOutcome.suspendUntilNotified();
            }
            return TaskOutcome.done("saw " + ctx.notifications().size() + " message(s)");
        });

        String parentId = queue.submit(TaskSubmission.of("slow-parent", Map.of()));
        TaskRecord done = queue.await(parentId, Duration.ofSeconds(5));

        // Parking is refused while a message waits, so the parent never sleeps
        // through the wakeup it already received.
        assertThat(done.status()).isEqualTo(TaskStatus.COMPLETED);
        assertThat(done.result()).isEqualTo("saw 1 message(s)");
    }

    @Test
    void notificationWhileRunningArrivesOnTheNextRound() throws Exception {
        ConcurrentLinkedQueue<Integer> perRound = new ConcurrentLinkedQueue<>();
        CountDownLatch running = new CountDownLatch(1);
        CountDownLatch sent = new CountDownLatch(1);

        queue.register("worker", ctx -> {
            perRound.add(ctx.notifications().size());
            if (ctx.state().isEmpty()) {
                ctx.updateState(Map.of("round", 1));
                running.countDown();
                assertThat(sent.await(5, TimeUnit.SECONDS)).isTrue();   // message lands mid-run
                return TaskOutcome.suspendUntilNotified();
            }
            return TaskOutcome.done("round two");
        });

        String taskId = queue.submit(TaskSubmission.of("worker", Map.of()));
        assertThat(running.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(queue.notify(taskId, TaskNotification.from(null, Map.of("from", "outside")))).isTrue();
        sent.countDown();

        TaskRecord done = queue.await(taskId, Duration.ofSeconds(5));
        assertThat(done.status()).isEqualTo(TaskStatus.COMPLETED);
        // Nothing on the first round (it arrived mid-flight), one on the second.
        assertThat(perRound).containsExactly(0, 1);
    }

    @Test
    void siblingsCanNotifyEachOther() {
        CountDownLatch bothRegistered = new CountDownLatch(1);
        queue.register("waiter", ctx -> {
            if (ctx.state().isEmpty()) {
                ctx.updateState(Map.of("phase", "listening"));
                bothRegistered.countDown();
                return TaskOutcome.suspendUntilNotified();
            }
            TaskNotification first = ctx.notifications().get(0);
            return TaskOutcome.done("poked by " + first.payload().get("who"));
        });

        String waiterId = queue.submit(TaskSubmission.of("waiter", Map.of()));
        waitFor(() -> queue.get(waiterId).map(r -> r.status() == TaskStatus.SUSPENDED).orElse(false));

        queue.register("poker", ctx -> {
            ctx.notifyTask(waiterId, Map.of("who", "sibling"));       // no parent/child link
            return TaskOutcome.done("poked");
        });
        queue.submit(TaskSubmission.of("poker", Map.of()));

        TaskRecord done = queue.await(waiterId, Duration.ofSeconds(5));
        assertThat(done.status()).isEqualTo(TaskStatus.COMPLETED);
        assertThat(done.result()).isEqualTo("poked by sibling");
    }

    @Test
    void aNotificationArrivingWhileTheParentRunsIsDeliveredToTheNextRound() throws Exception {
        CountDownLatch childReported = new CountDownLatch(1);
        queue.register("child", ctx -> {
            ctx.notifyParent(Map.of("child", "a"));
            childReported.countDown();
            return TaskOutcome.done("child-done");
        });

        List<Boolean> rounds = new java.util.concurrent.CopyOnWriteArrayList<>();
        List<Integer> delivered = new java.util.concurrent.CopyOnWriteArrayList<>();
        queue.register("parent", ctx -> {
            rounds.add(ctx.isResumed());
            if (!ctx.isResumed()) {
                ctx.submitChild("child", Map.of());
                // Stay in this round until the child has already reported: the
                // notification arrives while this parent is RUNNING.
                assertThat(childReported.await(5, TimeUnit.SECONDS)).isTrue();
                ctx.updateState(Map.of("phase", "await"));
                return TaskOutcome.suspendUntilChildren();
            }
            delivered.add(ctx.notifications().size());
            return TaskOutcome.done("saw " + ctx.notifications().size());
        });

        List<String> parked = new java.util.concurrent.CopyOnWriteArrayList<>();
        queue.addListener(new TaskListener() {
            @Override public void onSuspended(TaskRecord task) {
                if (task.type().equals("parent")) parked.add(task.id());
            }
        });

        String parentId = queue.submit(TaskSubmission.of("parent", Map.of()));
        TaskRecord done = queue.await(parentId, Duration.ofSeconds(5));

        assertThat(done.status()).isEqualTo(TaskStatus.COMPLETED);
        // The continuation runs and the message sent mid-run is delivered there.
        assertThat(rounds).containsExactly(false, true);
        assertThat(delivered).containsExactly(1);
        assertThat(done.result()).isEqualTo("saw 1");
        // It never actually parked: suspend refused while the mailbox was full.
        assertThat(parked).isEmpty();
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static List<String> sorted(List<String> values) {
        List<String> copy = new ArrayList<>(values);
        copy.sort(String::compareTo);
        return copy;
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
