package ai.mindconnect.taskqueue.bridge;

import ai.mindconnect.channel.Channel;
import ai.mindconnect.channel.ChannelRegistry;
import ai.mindconnect.taskqueue.TaskOutcome;
import ai.mindconnect.taskqueue.TaskRecord;
import ai.mindconnect.taskqueue.TaskStatus;
import ai.mindconnect.taskqueue.TaskSubmission;
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

import static org.assertj.core.api.Assertions.assertThat;

/** The live task view: listener as the source, channel as the delivery. */
class TaskChannelBridgeTest {

    private final ChannelRegistry channels = new ChannelRegistry();
    private final LocalTaskQueue queue = new LocalTaskQueue(new InMemoryTaskStore())
            .addListener(TaskChannelBridge.global(channels));

    @AfterEach
    void tearDown() {
        queue.close();
    }

    @Test
    void adminToolSeesLifecycleAndProgress() throws Exception {
        List<String> view = new CopyOnWriteArrayList<>();
        CountDownLatch done = new CountDownLatch(4);
        channels.<TaskEvent>channel(TaskChannelBridge.ALL_TASKS).subscribe(0, event -> {
            TaskEvent e = event.value();
            view.add(e.type() + (e.type() == TaskEvent.Type.STATE
                    ? "(" + e.task().state().get("phase") + ")" : ""));
            done.countDown();
        });

        queue.register("job", ctx -> {
            ctx.updateState(Map.of("phase", "searching"));
            return TaskOutcome.done("ok");
        });
        String id = queue.submit(TaskSubmission.of("job", Map.of()));
        queue.await(id, Duration.ofSeconds(5));

        assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
        // progress is visible mid-run, not just start and end
        assertThat(view).containsExactly("SUBMITTED", "STARTED", "STATE(searching)", "TERMINAL");
    }

    @Test
    void aLateAdminToolReplaysWhatItMissed() throws Exception {
        queue.register("job", ctx -> TaskOutcome.done("ok"));
        String id = queue.submit(TaskSubmission.of("job", Map.of()));
        queue.await(id, Duration.ofSeconds(5));

        // the tool starts only now — and still sees the finished task
        List<TaskEvent.Type> replayed = new CopyOnWriteArrayList<>();
        CountDownLatch three = new CountDownLatch(3);
        channels.<TaskEvent>channel(TaskChannelBridge.ALL_TASKS).subscribe(0, event -> {
            replayed.add(event.value().type());
            three.countDown();
        });

        assertThat(three.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(replayed).containsExactly(
                TaskEvent.Type.SUBMITTED, TaskEvent.Type.STARTED, TaskEvent.Type.TERMINAL);
    }

    @Test
    void suspendAndWakeAreVisibleToo() throws Exception {
        List<TaskEvent.Type> view = new CopyOnWriteArrayList<>();
        channels.<TaskEvent>channel(TaskChannelBridge.ALL_TASKS)
                .subscribe(0, event -> view.add(event.value().type()));

        CountDownLatch childGate = new CountDownLatch(1);
        queue.register("child", ctx -> { childGate.await(); return TaskOutcome.done("done"); });
        queue.register("parent", ctx -> {
            if (ctx.state().isEmpty()) {
                String childId = queue.submit(TaskSubmission.of("child", Map.of()).withPriority(1));
                ctx.updateState(Map.of("childId", childId));
                return TaskOutcome.suspendUntil(childId);
            }
            return TaskOutcome.done("resumed");
        });

        String parentId = queue.submit(TaskSubmission.of("parent", Map.of()));
        TimeUnit.MILLISECONDS.sleep(150);
        childGate.countDown();
        queue.await(parentId, Duration.ofSeconds(5));
        TimeUnit.MILLISECONDS.sleep(100);

        assertThat(view).contains(TaskEvent.Type.SUSPENDED, TaskEvent.Type.WOKEN);
    }

    @Test
    void routingLetsAClientWatchOneKindOfTask() throws Exception {
        LocalTaskQueue routed = new LocalTaskQueue(new InMemoryTaskStore())
                .addListener(TaskChannelBridge.routed(channels, TaskRecord::type));
        try {
            routed.register("turns", ctx -> TaskOutcome.done("ok"));
            routed.register("tools", ctx -> TaskOutcome.done("ok"));
            List<String> onlyTools = new CopyOnWriteArrayList<>();
            channels.<TaskEvent>channel("tools").subscribe(0, event -> onlyTools.add(event.value().task().type()));

            routed.await(routed.submit(TaskSubmission.of("turns", Map.of())), Duration.ofSeconds(5));
            routed.await(routed.submit(TaskSubmission.of("tools", Map.of())), Duration.ofSeconds(5));
            TimeUnit.MILLISECONDS.sleep(100);

            assertThat(onlyTools).isNotEmpty().allMatch("tools"::equals);
        } finally {
            routed.close();
        }
    }

    @Test
    void aSlowViewerNeverSlowsTheQueue() {
        ChannelRegistry tiny = new ChannelRegistry(2048, 2);   // 2-slot subscriber queue
        LocalTaskQueue fast = new LocalTaskQueue(new InMemoryTaskStore())
                .addListener(TaskChannelBridge.global(tiny));
        CountDownLatch stuck = new CountDownLatch(1);
        Channel<TaskEvent> channel = tiny.<TaskEvent>channel(TaskChannelBridge.ALL_TASKS);
        channel.subscribe(0, event -> {
            try {
                stuck.await();                                  // viewer hangs forever
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        try {
            fast.register("job", ctx -> TaskOutcome.done("ok"));
            long start = System.nanoTime();
            for (int i = 0; i < 200; i++) {
                fast.await(fast.submit(TaskSubmission.of("job", Map.of())), Duration.ofSeconds(5));
            }
            long tookMs = (System.nanoTime() - start) / 1_000_000;

            assertThat(tookMs).isLessThan(5000);                // drop-oldest, never block
        } finally {
            stuck.countDown();
            fast.close();
        }
    }

    @Test
    void terminalEventCarriesTheFinalRecord() throws Exception {
        CountDownLatch terminal = new CountDownLatch(1);
        List<TaskRecord> finished = new CopyOnWriteArrayList<>();
        channels.<TaskEvent>channel(TaskChannelBridge.ALL_TASKS).subscribe(0, event -> {
            if (event.value().type() == TaskEvent.Type.TERMINAL) {
                finished.add(event.value().task());
                terminal.countDown();
            }
        });

        queue.register("boom", ctx -> { throw new IllegalStateException("kaputt"); });
        queue.await(queue.submit(TaskSubmission.of("boom", Map.of())), Duration.ofSeconds(5));

        assertThat(terminal.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(finished.get(0).status()).isEqualTo(TaskStatus.FAILED);
        assertThat(finished.get(0).failure().message()).isEqualTo("kaputt");
    }
}
