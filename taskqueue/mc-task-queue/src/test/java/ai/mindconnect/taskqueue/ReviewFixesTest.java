package ai.mindconnect.taskqueue;

import ai.mindconnect.taskqueue.local.LocalTaskQueue;
import ai.mindconnect.taskqueue.memory.InMemoryTaskStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** The behaviors pinned down by the code review — each was a confirmed bug. */
class ReviewFixesTest {

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
    void childlessSuspendUntilChildrenRequeuesInsteadOfParkingForever() {
        // The documented promise: "a task with no children at all is simply
        // requeued" — it used to park SUSPENDED, indistinguishable from a
        // notification wait nobody would ever end.
        queue.register("fan-out", ctx -> {
            if (!ctx.isResumed()) {
                // conditionally spawns children; today there are none
                return TaskOutcome.suspendUntilChildren();
            }
            return TaskOutcome.done("nothing to fan out");
        });

        String id = queue.submit(TaskSubmission.of("fan-out", Map.of()));
        TaskRecord done = queue.await(id, Duration.ofSeconds(5));

        assertThat(done.status()).isEqualTo(TaskStatus.COMPLETED);
        assertThat(done.result()).isEqualTo("nothing to fan out");
    }

    @Test
    void cancelArrivingDuringTheRoundKillsInsteadOfParking() {
        // Store-level: the row is RUNNING with the cancel flag set (exactly
        // the state a cancel() in run()'s race window produces) — suspend()
        // must finish the kill, not park a task that was told to die.
        TaskRecord running = TaskRecord.queued("t1",
                TaskSubmission.of("t", Map.of())).claimed("local").withCancelRequested();
        store.save(running);

        TaskRecord after = store.suspend("t1", TaskOutcome.suspendUntilNotified());

        assertThat(after.status()).isEqualTo(TaskStatus.CANCELLED);
    }

    @Test
    void terminalSaveKeepsAConcurrentlyArrivedMessageAndCancelFlag() {
        // save() is built from a snapshot; mail and cancel that landed after
        // the snapshot must survive it.
        TaskRecord running = TaskRecord.queued("t2", TaskSubmission.of("t", Map.of())).claimed("local");
        store.save(running);
        store.notify("t2", TaskNotification.from("someone", Map.of("k", "v")));
        store.requestCancel("t2");

        store.save(running.completed("done"));   // the stale snapshot

        TaskRecord row = store.find("t2").orElseThrow();
        assertThat(row.notifications()).hasSize(1);
        assertThat(row.cancelRequested()).isTrue();
    }

    @Test
    void startupSweepWakesTheWaitersOfSweptTasks() {
        // Crash state: child RUNNING, parent SUSPENDED on it. A new queue's
        // startup sweep fails the child — and must wake the parent.
        TaskRecord child = TaskRecord.queued("child", TaskSubmission.of("t", Map.of())).claimed("local");
        store.save(child);
        TaskRecord parent = TaskRecord.queued("parent", TaskSubmission.of("t", Map.of()))
                .claimed("local").suspended(Set.of("child"));
        store.save(parent);
        queue.close();

        queue = new LocalTaskQueue(store);      // sweep runs in the constructor

        assertThat(store.find("child").orElseThrow().status()).isEqualTo(TaskStatus.FAILED);
        assertThat(store.find("parent").orElseThrow().status()).isEqualTo(TaskStatus.QUEUED);
    }
}
