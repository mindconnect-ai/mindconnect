package ai.mindconnect.taskqueue;

import ai.mindconnect.taskqueue.local.LocalTaskQueue;
import ai.mindconnect.taskqueue.memory.InMemoryTaskStore;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalTaskQueueTest {

    private final InMemoryTaskStore store = new InMemoryTaskStore();
    private LocalTaskQueue queue = new LocalTaskQueue(store);

    @AfterEach
    void tearDown() {
        queue.close();
    }

    @Test
    void aCallerChosenIdNamesTheTaskAndResubmittingItIsIdempotent() {
        // The id names the WORK: "task_turn_<turnId>" makes the task findable
        // from domain state alone, and a resume that cannot remember whether
        // it already submitted simply submits again and gets the same task.
        queue.register("echo", ctx -> TaskOutcome.done("ran"));

        String first = queue.submit(TaskSubmission.of("echo", Map.of()).withId("task_turn_42"));
        assertThat(first).isEqualTo("task_turn_42");
        queue.await(first, Duration.ofSeconds(5));

        String second = queue.submit(TaskSubmission.of("echo", Map.of()).withId("task_turn_42"));
        assertThat(second).isEqualTo("task_turn_42");
        // still ONE task, one execution — not a twin
        assertThat(queue.get("task_turn_42").orElseThrow().attempt()).isEqualTo(1);
    }

    @Test
    void byStatusExposesTheQueueAsTheRegistryOfRunningWork() throws Exception {
        var started = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        queue.register("slow", ctx -> {
            started.countDown();
            try {
                release.await(5, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return TaskOutcome.done(null);
        });

        String id = queue.submit(TaskSubmission.of("slow", Map.of("sessionId", "s1")).withId("task_s"));
        assertThat(started.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();

        var running = queue.byStatus(TaskStatus.RUNNING, Integer.MAX_VALUE);
        assertThat(running).extracting(TaskRecord::id).contains("task_s");
        assertThat(running.stream().filter(r -> r.id().equals(id)).findFirst().orElseThrow()
                .payload()).containsEntry("sessionId", "s1");
        release.countDown();
        queue.await(id, Duration.ofSeconds(5));
    }

    @Test
    void submitAwaitComplete() {
        queue.register("echo", ctx -> TaskOutcome.done("echo: " + ctx.task().payload().get("text")));

        String id = queue.submit(TaskSubmission.of("echo", Map.of("text", "hi")));
        TaskRecord done = queue.await(id, Duration.ofSeconds(5));

        assertThat(done.status()).isEqualTo(TaskStatus.COMPLETED);
        assertThat(done.result()).isEqualTo("echo: hi");
        assertThat(done.attempt()).isEqualTo(1);
        assertThat(done.startedAt()).isNotNull();
        assertThat(done.endedAt()).isNotNull();
    }

    @Test
    void workerExceptionBecomesFailed() {
        queue.register("boom", ctx -> { throw new IllegalStateException("kaputt"); });

        TaskRecord done = queue.await(queue.submit(TaskSubmission.of("boom", Map.of())),
                Duration.ofSeconds(5));

        assertThat(done.status()).isEqualTo(TaskStatus.FAILED);
        assertThat(done.failure().message()).isEqualTo("kaputt");
        // The queue captured the exception itself — the worker only threw.
        assertThat(done.failure().type()).isEqualTo("java.lang.IllegalStateException");
        assertThat(done.failure().stackTrace()).contains("workerExceptionBecomesFailed");
    }

    @Test
    void priorityOverFifo_childrenOvertakeRoots() throws Exception {
        queue.close();
        queue = new LocalTaskQueue(store, 1);              // one lane → order observable
        List<String> order = new CopyOnWriteArrayList<>();
        CountDownLatch gate = new CountDownLatch(1);
        queue.register("blocker", ctx -> { gate.await(); return TaskOutcome.done(null); });
        queue.register("job", ctx -> {
            order.add((String) ctx.task().payload().get("name"));
            return TaskOutcome.done(null);
        });

        queue.submit(TaskSubmission.of("blocker", Map.of()));                    // occupies the lane
        String root = queue.submit(TaskSubmission.of("job", Map.of("name", "root")));       // prio 0
        String child = queue.submit(TaskSubmission.of("job", Map.of("name", "child"))
                .withPriority(1).withParent(root));                              // prio 1 = depth
        gate.countDown();

        queue.await(root, Duration.ofSeconds(5));
        queue.await(child, Duration.ofSeconds(5));
        assertThat(order).containsExactly("child", "root");
    }

    @Test
    void cancelQueuedNeverRuns() throws Exception {
        queue.close();
        queue = new LocalTaskQueue(store, 1);
        CountDownLatch gate = new CountDownLatch(1);
        List<String> ran = new CopyOnWriteArrayList<>();
        queue.register("blocker", ctx -> { gate.await(); return TaskOutcome.done(null); });
        queue.register("job", ctx -> { ran.add("ran"); return TaskOutcome.done(null); });

        queue.submit(TaskSubmission.of("blocker", Map.of()));
        String queued = queue.submit(TaskSubmission.of("job", Map.of()));

        assertThat(queue.cancel(queued)).isTrue();
        TaskRecord record = queue.await(queued, Duration.ofSeconds(5));
        gate.countDown();

        assertThat(record.status()).isEqualTo(TaskStatus.CANCELLED);
        assertThat(ran).isEmpty();
    }

    @Test
    void cancelRunningIsCooperative() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        queue.register("loop", ctx -> {
            started.countDown();
            while (!ctx.cancelRequested()) {
                TimeUnit.MILLISECONDS.sleep(20);
            }
            return TaskOutcome.done("stopped cooperatively");
        });

        String id = queue.submit(TaskSubmission.of("loop", Map.of()));
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(queue.cancel(id)).isTrue();

        TaskRecord done = queue.await(id, Duration.ofSeconds(5));
        assertThat(done.status()).isEqualTo(TaskStatus.CANCELLED);
    }

    @Test
    void deepAwaitChain_noDeadlockWithUnboundedWorkers() {
        queue.register("chain", ctx -> {
            int depth = (int) ctx.task().payload().get("depth");
            if (depth >= 20) return TaskOutcome.done("bottom");
            String childId = queue.submit(
                    TaskSubmission.of("chain", Map.of("depth", depth + 1))
                            .withPriority(depth + 1)
                            .withParent(ctx.task().id()));
            return TaskOutcome.done(queue.await(childId, Duration.ofSeconds(10)).result());   // variant (a)
        });

        TaskRecord done = queue.await(
                queue.submit(TaskSubmission.of("chain", Map.of("depth", 0))),
                Duration.ofSeconds(10));

        assertThat(done.status()).isEqualTo(TaskStatus.COMPLETED);
        assertThat(done.result()).isEqualTo("bottom");
    }

    @Test
    void unregisteredTypeStaysQueued() {
        String id = queue.submit(TaskSubmission.of("nobody-handles-this", Map.of()));

        assertThatThrownBy(() -> queue.await(id, Duration.ofMillis(300)))
                .isInstanceOf(TaskQueueException.class)
                .hasMessageContaining("Timed out");
        assertThat(queue.get(id).orElseThrow().status()).isEqualTo(TaskStatus.QUEUED);
    }

    @Test
    void startupSweepFailsOrphanedRunningTasks() {
        InMemoryTaskStore crashed = new InMemoryTaskStore();
        crashed.save(TaskRecord.queued("task_orphan", TaskSubmission.of("job", Map.of())).claimed("local"));
        assertThat(crashed.find("task_orphan").orElseThrow().status()).isEqualTo(TaskStatus.RUNNING);

        try (LocalTaskQueue restarted = new LocalTaskQueue(crashed)) {
            TaskRecord swept = restarted.get("task_orphan").orElseThrow();
            assertThat(swept.status()).isEqualTo(TaskStatus.FAILED);
            assertThat(swept.failure().message()).isEqualTo("interrupted by restart");
        }
    }

    @Test
    void awaitOnAlreadyTerminalTaskReturnsImmediately() {
        queue.register("echo", ctx -> TaskOutcome.done("done"));
        String id = queue.submit(TaskSubmission.of("echo", Map.of()));
        queue.await(id, Duration.ofSeconds(5));

        Instant before = Instant.now();
        TaskRecord again = queue.await(id, Duration.ofSeconds(5));
        assertThat(again.status()).isEqualTo(TaskStatus.COMPLETED);
        assertThat(Duration.between(before, Instant.now())).isLessThan(Duration.ofMillis(500));
    }

    // ── continuation (variant b): park without a thread, resume via checkpoint ──

    @Test
    void parentSuspendsAndResumesWithState() throws Exception {
        CountDownLatch childGate = new CountDownLatch(1);
        queue.register("child", ctx -> {
            childGate.await();
            return TaskOutcome.done("child-result");
        });
        queue.register("parent", ctx -> {
            if (ctx.state().isEmpty()) {                             // first execution
                String childId = queue.submit(TaskSubmission.of("child", Map.of())
                        .withPriority(1).withParent(ctx.task().id()));
                ctx.updateState(Map.of("phase", "collect", "childId", childId));
                return TaskOutcome.suspendUntil(childId);
            }
            // resumed execution: the state tells us where we were
            assertThat(ctx.state()).containsEntry("phase", "collect");
            String childId = (String) ctx.state().get("childId");
            TaskRecord child = queue.get(childId).orElseThrow();
            return TaskOutcome.done("combined: " + child.result());
        });

        String parentId = queue.submit(TaskSubmission.of("parent", Map.of()));

        // parent suspends: no thread, state persisted and visible from outside
        waitForStatus(parentId, TaskStatus.SUSPENDED);
        TaskRecord suspended = queue.get(parentId).orElseThrow();
        assertThat(suspended.state()).containsEntry("phase", "collect");
        assertThat(suspended.waitingFor()).hasSize(1);

        childGate.countDown();
        TaskRecord done = queue.await(parentId, Duration.ofSeconds(5));

        assertThat(done.status()).isEqualTo(TaskStatus.COMPLETED);
        assertThat(done.result()).isEqualTo("combined: child-result");
        assertThat(done.attempt()).isEqualTo(2);                     // one park, one resume
    }

    @Test
    void suspendOnAlreadyTerminalTasksRequeuesImmediately() {
        queue.register("fast", ctx -> TaskOutcome.done("ok"));
        queue.register("parent", ctx -> {
            if (ctx.state().isEmpty()) {
                String childId = queue.submit(TaskSubmission.of("fast", Map.of()).withPriority(1));
                queue.await(childId, Duration.ofSeconds(5));         // child ALREADY terminal…
                ctx.updateState(Map.of("childId", childId));
                return TaskOutcome.suspendUntil(childId);            // …then we suspend on it
            }
            return TaskOutcome.done("no lost wakeup");
        });

        TaskRecord done = queue.await(queue.submit(TaskSubmission.of("parent", Map.of())),
                Duration.ofSeconds(5));

        assertThat(done.status()).isEqualTo(TaskStatus.COMPLETED);
        assertThat(done.result()).isEqualTo("no lost wakeup");
    }

    @Test
    void cancellingSuspendedParentCascadesToChildren() throws Exception {
        CountDownLatch childStarted = new CountDownLatch(1);
        queue.register("slow-child", ctx -> {
            childStarted.countDown();
            while (!ctx.cancelRequested()) {
                TimeUnit.MILLISECONDS.sleep(20);
            }
            return TaskOutcome.done(null);
        });
        queue.register("parent", ctx -> {
            if (ctx.state().isEmpty()) {
                String childId = ctx.submitChild("slow-child", Map.of());
                ctx.updateState(Map.of("phase", "await"));
                return TaskOutcome.suspendUntil(childId);
            }
            return TaskOutcome.done("should not get here");
        });

        String parentId = queue.submit(TaskSubmission.of("parent", Map.of()));
        waitForStatus(parentId, TaskStatus.SUSPENDED);
        assertThat(childStarted.await(5, TimeUnit.SECONDS)).isTrue();
        String childId = queue.children(parentId).get(0).id();      // the tree, not a remembered id

        assertThat(queue.cancel(parentId)).isTrue();                 // task-manager kill

        TaskRecord parent = queue.await(parentId, Duration.ofSeconds(5));
        TaskRecord child = queue.await(childId, Duration.ofSeconds(5));
        assertThat(parent.status()).isEqualTo(TaskStatus.CANCELLED);
        assertThat(child.status()).isEqualTo(TaskStatus.CANCELLED);
    }

    private void waitForStatus(String taskId, TaskStatus expected) throws InterruptedException {
        for (int i = 0; i < 250; i++) {
            if (queue.get(taskId).orElseThrow().status() == expected) return;
            TimeUnit.MILLISECONDS.sleep(20);
        }
        throw new AssertionError("Task " + taskId + " never reached " + expected
                + " (is " + queue.get(taskId).orElseThrow().status() + ")");
    }

    @Test
    void liveStateIsVisibleWhileRunning() throws Exception {
        CountDownLatch phaseOne = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        queue.register("progress", ctx -> {
            ctx.updateState(Map.of("phase", "searching", "round", 3));
            phaseOne.countDown();
            release.await();
            return TaskOutcome.done("ok");
        });

        String id = queue.submit(TaskSubmission.of("progress", Map.of()));
        assertThat(phaseOne.await(5, TimeUnit.SECONDS)).isTrue();

        // the admin/task-manager view: internal state, live, mid-run
        TaskRecord running = queue.get(id).orElseThrow();
        assertThat(running.status()).isEqualTo(TaskStatus.RUNNING);
        assertThat(running.state()).containsEntry("phase", "searching").containsEntry("round", 3);

        release.countDown();
        TaskRecord done = queue.await(id, Duration.ofSeconds(5));
        assertThat(done.state()).containsEntry("phase", "searching");   // post-mortem context
    }
}