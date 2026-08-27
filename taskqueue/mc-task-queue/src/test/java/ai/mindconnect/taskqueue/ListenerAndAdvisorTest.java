package ai.mindconnect.taskqueue;

import ai.mindconnect.taskqueue.local.LocalTaskQueue;
import ai.mindconnect.taskqueue.memory.InMemoryTaskStore;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ListenerAndAdvisorTest {

    private final LocalTaskQueue queue = new LocalTaskQueue(new InMemoryTaskStore());

    @AfterEach
    void tearDown() {
        queue.close();
    }

    // ── Listener: observation plane ─────────────────────────────────────────

    @Test
    void listenerSeesTheFullSuspendResumeLifecycle() throws Exception {
        List<String> events = new CopyOnWriteArrayList<>();
        queue.addListener(new TaskListener() {
            @Override public void onSubmitted(TaskRecord t) { events.add(t.type() + ":submitted"); }
            @Override public void onStarted(TaskRecord t) { events.add(t.type() + ":started:" + t.attempt()); }
            @Override public void onSuspended(TaskRecord t) { events.add(t.type() + ":suspended"); }
            @Override public void onWoken(TaskRecord t) { events.add(t.type() + ":woken"); }
            @Override public void onTerminal(TaskRecord t) { events.add(t.type() + ":" + t.status()); }
        });
        CountDownLatch childGate = new CountDownLatch(1);
        queue.register("child", ctx -> { childGate.await(); return TaskOutcome.done("ok"); });
        queue.register("parent", ctx -> {
            if (ctx.state().isEmpty()) {
                String childId = queue.submit(TaskSubmission.of("child", Map.of())
                        .withPriority(1).withParent(ctx.task().id()));
                ctx.updateState(Map.of("childId", childId));
                return TaskOutcome.suspendUntil(childId);
            }
            return TaskOutcome.done("done");
        });

        String parentId = queue.submit(TaskSubmission.of("parent", Map.of()));
        TimeUnit.MILLISECONDS.sleep(150);          // let parent suspend, child start
        childGate.countDown();
        queue.await(parentId, Duration.ofSeconds(5));

        assertThat(events).contains(
                "parent:submitted", "parent:started:1", "child:submitted", "parent:suspended",
                "child:started:1", "child:COMPLETED", "parent:woken", "parent:started:2",
                "parent:COMPLETED");
    }

    @Test
    void brokenListenerNeverBreaksTheQueue() {
        queue.addListener(new TaskListener() {
            @Override public void onSubmitted(TaskRecord t) { throw new IllegalStateException("broken"); }
            @Override public void onTerminal(TaskRecord t) { throw new IllegalStateException("broken"); }
        });
        queue.register("echo", ctx -> TaskOutcome.done("fine"));

        TaskRecord done = queue.await(queue.submit(TaskSubmission.of("echo", Map.of())),
                Duration.ofSeconds(5));

        assertThat(done.status()).isEqualTo(TaskStatus.COMPLETED);
    }

    // ── Advisor: policy plane ───────────────────────────────────────────────

    @Test
    void submitAdvisorEnrichesAndRejects() {
        queue.addAdvisor(new TaskAdvisor() {
            @Override public TaskSubmission beforeSubmit(TaskSubmission s) {
                if ("forbidden".equals(s.type())) {
                    throw new TaskQueueException("type not allowed");
                }
                return s.withPriority(42);                       // policy: bump everything
            }
        });
        queue.register("echo", ctx -> TaskOutcome.done("ok"));

        String id = queue.submit(TaskSubmission.of("echo", Map.of()));
        assertThat(queue.get(id).orElseThrow().priority()).isEqualTo(42);

        assertThatThrownBy(() -> queue.submit(TaskSubmission.of("forbidden", Map.of())))
                .isInstanceOf(TaskQueueException.class)
                .hasMessageContaining("not allowed");
    }

    @Test
    void executeAdvisorCanShortCircuitAndRetry() {
        AtomicInteger workerRuns = new AtomicInteger();
        queue.addAdvisor(new TaskAdvisor() {
            @Override public TaskOutcome aroundExecute(TaskContext ctx, Execution chain) throws Exception {
                if ("blocked".equals(ctx.task().type())) {
                    return TaskOutcome.done("intercepted");      // worker never runs
                }
                try {
                    return chain.proceed(ctx);
                } catch (Exception first) {
                    return chain.proceed(ctx);                   // one retry
                }
            }
        });
        queue.register("blocked", ctx -> { workerRuns.incrementAndGet(); return TaskOutcome.done("real"); });
        queue.register("flaky", ctx -> {
            if (workerRuns.incrementAndGet() == 1) throw new IllegalStateException("first try fails");
            return TaskOutcome.done("second try wins");
        });

        TaskRecord blocked = queue.await(queue.submit(TaskSubmission.of("blocked", Map.of())),
                Duration.ofSeconds(5));
        assertThat(blocked.result()).isEqualTo("intercepted");
        assertThat(workerRuns.get()).isZero();

        TaskRecord flaky = queue.await(queue.submit(TaskSubmission.of("flaky", Map.of())),
                Duration.ofSeconds(5));
        assertThat(flaky.status()).isEqualTo(TaskStatus.COMPLETED);
        assertThat(flaky.result()).isEqualTo("second try wins");
    }

    @Test
    void advisorOrderLowerIsOutermost() {
        List<String> trace = new CopyOnWriteArrayList<>();
        queue.addAdvisor(new TaskAdvisor() {
            @Override public TaskOutcome aroundExecute(TaskContext ctx, Execution chain) throws Exception {
                trace.add("inner:in"); TaskOutcome out = chain.proceed(ctx); trace.add("inner:out"); return out;
            }
            @Override public int order() { return 10; }
        });
        queue.addAdvisor(new TaskAdvisor() {
            @Override public TaskOutcome aroundExecute(TaskContext ctx, Execution chain) throws Exception {
                trace.add("outer:in"); TaskOutcome out = chain.proceed(ctx); trace.add("outer:out"); return out;
            }
            @Override public int order() { return -10; }
        });
        queue.register("echo", ctx -> { trace.add("worker"); return TaskOutcome.done(null); });

        queue.await(queue.submit(TaskSubmission.of("echo", Map.of())), Duration.ofSeconds(5));

        assertThat(trace).containsExactly("outer:in", "inner:in", "worker", "inner:out", "outer:out");
    }
}
