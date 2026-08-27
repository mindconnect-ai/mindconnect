package ai.mindconnect.taskqueue.jdbc;

import ai.mindconnect.channel.Channel;
import ai.mindconnect.channel.jdbc.JdbcChannelStore;
import ai.mindconnect.taskqueue.TaskOutcome;
import ai.mindconnect.taskqueue.TaskRecord;
import ai.mindconnect.taskqueue.TaskStatus;
import ai.mindconnect.taskqueue.TaskSubmission;
import ai.mindconnect.taskqueue.local.LocalTaskQueue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;

import javax.sql.DataSource;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Integration test against a real Postgres. Skipped (via assumption) when no
 * database answers — CI runs without one; locally run e.g.
 * {@code podman run -d -p 5433:5432 -e POSTGRES_PASSWORD=test postgres:17}
 * and set {@code MC_TASKQUEUE_TEST_URL} if not using the default below.
 *
 * <p>The point of every test here: the QUEUE is unchanged — two
 * {@code LocalTaskQueue}s on one {@code JdbcTaskStore} are already a cluster.
 */
class JdbcTaskStoreTest {

    private static final String URL = System.getenv().getOrDefault(
            "MC_TASKQUEUE_TEST_URL", "jdbc:postgresql://localhost:5433/postgres");
    private static final String USER = System.getenv().getOrDefault("MC_TASKQUEUE_TEST_USER", "postgres");
    private static final String PASSWORD = System.getenv().getOrDefault("MC_TASKQUEUE_TEST_PASSWORD", "test");

    private DataSource dataSource;
    private LocalTaskQueue queue;

    @BeforeAll
    static void requiresPostgres() {
        assumeTrue(reachable(), "no Postgres reachable — skipping");
    }

    private static boolean reachable() {
        try (var c = DriverManager.getConnection(URL, USER, PASSWORD)) {
            return c.isValid(2);
        } catch (Exception e) {
            return false;
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        var ds = new PGSimpleDataSource();
        ds.setUrl(URL);
        ds.setUser(USER);
        ds.setPassword(PASSWORD);
        this.dataSource = ds;
        try (var c = ds.getConnection(); var s = c.createStatement()) {
            s.execute("DROP TABLE IF EXISTS mc_task; "
                    + "DROP TABLE IF EXISTS mc_channel_event; DROP TABLE IF EXISTS mc_channel");
        }
        queue = new LocalTaskQueue(store("node-1", Duration.ofMinutes(1)));
    }

    private JdbcTaskStore store(String nodeId, Duration lease) {
        return new JdbcTaskStore(dataSource, nodeId, lease).initSchema();
    }

    @AfterEach
    void tearDown() {
        if (queue != null) queue.close();
    }

    @Test
    void submitClaimCompleteRoundTrip() {
        queue.register("echo", ctx -> TaskOutcome.done("echo: " + ctx.task().payload().get("text")));

        String id = queue.submit(TaskSubmission.of("echo", Map.of("text", "hi")));
        TaskRecord done = queue.await(id, Duration.ofSeconds(10));

        assertThat(done.status()).isEqualTo(TaskStatus.COMPLETED);
        assertThat(done.result()).isEqualTo("echo: hi");
        assertThat(done.attempt()).isEqualTo(1);
    }

    @Test
    void parentSuspendsAndWakesAcrossTheStore() {
        queue.register("child", ctx -> {
            ctx.notifyParent(Map.of("from", ctx.task().payload().get("name")));
            return TaskOutcome.done("child done");
        });
        queue.register("parent", ctx -> {
            if (!ctx.isResumed()) {
                ctx.submitChild("child", Map.of("name", "a"));
                ctx.submitChild("child", Map.of("name", "b"));
                return TaskOutcome.suspendUntilChildren();
            }
            var open = ctx.task().waitingFor();
            return open.isEmpty()
                    ? TaskOutcome.done("notified " + ctx.notifications().size() + "+, all done")
                    : TaskOutcome.suspendUntil(open);
        });

        String id = queue.submit(TaskSubmission.of("parent", Map.of()));
        TaskRecord done = queue.await(id, Duration.ofSeconds(10));

        assertThat(done.status()).isEqualTo(TaskStatus.COMPLETED);
        assertThat(done.result()).contains("all done");
        assertThat(queue.children(id)).hasSize(2)
                .allMatch(c -> c.status() == TaskStatus.COMPLETED);
    }

    @Test
    void twoQueuesOnOneStoreShareTheWork() throws Exception {
        // Second node: its own queue instance, the same table.
        try (LocalTaskQueue other = new LocalTaskQueue(store("node-2", Duration.ofMinutes(1)))) {
            var claimed = new java.util.concurrent.ConcurrentHashMap<String, String>();
            var latch = new CountDownLatch(8);
            for (var entry : Map.of("node-1", queue, "node-2", other).entrySet()) {
                entry.getValue().register("work", ctx -> {
                    claimed.put(ctx.task().id(), entry.getKey());
                    latch.countDown();
                    return TaskOutcome.done("done");
                });
            }
            for (int i = 0; i < 8; i++) {
                queue.submit(TaskSubmission.of("work", Map.of("i", i)));
                other.nudge();
            }
            assertThat(latch.await(15, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThat(claimed).hasSize(8);           // every task ran exactly once
        }
    }

    @Test
    void expiredLeaseIsReclaimedAndRetriedElsewhere() throws Exception {
        // node-1 has a 300ms lease, never renews (maintenance interval is huge),
        // and hangs forever — the store must hand the task to node-2.
        queue.close();
        queue = new LocalTaskQueue(store("node-1", Duration.ofMillis(300)))
                .withMaintenanceInterval(Duration.ofHours(1));
        var hang = new CountDownLatch(1);
        queue.register("flaky", ctx -> {
            if (ctx.task().attempt() == 1) hang.await();   // "the node died"
            return TaskOutcome.done("recovered on attempt " + ctx.task().attempt());
        });
        String id = queue.submit(TaskSubmission.of("flaky", Map.of()).withMaxAttempts(2));
        // Only start the rescuer once node-1 has claimed and hangs — otherwise
        // node-2 wins the first claim and there is nothing to recover.
        long deadline = System.currentTimeMillis() + 5000;
        while (queue.get(id).orElseThrow().status() != TaskStatus.RUNNING) {
            if (System.currentTimeMillis() > deadline) throw new AssertionError("node-1 never claimed");
            Thread.sleep(20);
        }

        try (LocalTaskQueue rescuer = new LocalTaskQueue(store("node-2", Duration.ofMinutes(1)))
                .withMaintenanceInterval(Duration.ofMillis(200))) {
            rescuer.register("flaky", ctx -> TaskOutcome.done("recovered on attempt " + ctx.task().attempt()));
            // Poll the STORE, not await(): await is in-process, and after the
            // reclaim EITHER node may claim attempt 2 (node-1 no longer hangs).
            long doneBy = System.currentTimeMillis() + 20000;
            TaskRecord done;
            do {
                Thread.sleep(100);
                done = rescuer.get(id).orElseThrow();
            } while (!done.status().terminal() && System.currentTimeMillis() < doneBy);
            assertThat(done.status()).isEqualTo(TaskStatus.COMPLETED);
            assertThat(done.result()).isEqualTo("recovered on attempt 2");
            // (completion clears the recorded lease-expiry failure — success
            // means the last attempt had none, same as the in-memory store)
        } finally {
            hang.countDown();
        }
    }

    @Test
    void lostWakeIsRecoveredBySweep() {
        // Build the pathological state by hand: a parent SUSPENDED on a child
        // that is already FAILED — the wake that should have fired is gone.
        var store = store("node-1", Duration.ofMinutes(1));
        TaskRecord child = TaskRecord.queued("child-1",
                TaskSubmission.of("t", Map.of())).claimed("node-x").failed(
                        ai.mindconnect.taskqueue.TaskFailure.of("gone", 1));
        store.save(child);
        TaskRecord parent = TaskRecord.queued("parent-1",
                TaskSubmission.of("t", Map.of())).claimed("node-x")
                .suspended(java.util.Set.of("child-1"));
        store.save(parent);

        var recovered = store.recoverExpired();

        assertThat(recovered).extracting(TaskRecord::id).contains("parent-1");
        TaskRecord woken = store.find("parent-1").orElseThrow();
        assertThat(woken.status()).isEqualTo(TaskStatus.QUEUED);
        assertThat(woken.waitingFor()).isEmpty();
    }

    @Test
    void aFencedOutNodeCannotWriteTransitions() {
        // node-1 claims; node-2 (a different owner) must be refused on every
        // transition write while the row RUNS under node-1's lease — the
        // review found retry() flipping a re-claimed task back to QUEUED.
        var store1 = store("node-1", Duration.ofMinutes(1));
        var store2 = new JdbcTaskStore(dataSource, "node-2", Duration.ofMinutes(1));

        store1.save(TaskRecord.queued("fenced-1", TaskSubmission.of("t", Map.of())));
        TaskRecord claimed = store1.claimNext(java.util.Set.of("t")).orElseThrow();

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        store2.retry(claimed.id(), Duration.ZERO,
                                ai.mindconnect.taskqueue.TaskFailure.of("stale", 1)))
                .isInstanceOf(ai.mindconnect.taskqueue.LeaseLostException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        store2.updateState(claimed.id(), Map.of("step", 99)))
                .isInstanceOf(ai.mindconnect.taskqueue.LeaseLostException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        store2.save(claimed.completed("zombie result")))
                .isInstanceOf(ai.mindconnect.taskqueue.LeaseLostException.class);

        TaskRecord row = store1.find(claimed.id()).orElseThrow();
        assertThat(row.status()).isEqualTo(TaskStatus.RUNNING);   // untouched
        // ...and the rightful owner still can:
        store1.updateState(claimed.id(), Map.of("step", 1));
        store1.save(claimed.completed("real result"));
        assertThat(store1.find(claimed.id()).orElseThrow().status())
                .isEqualTo(TaskStatus.COMPLETED);
    }

    @Test
    void channelStoreAssignsOneSeqSpacePerChannel() {
        var store = new JdbcChannelStore<>(dataSource, Map.class).initSchema();

        assertThat(store.append("tasks", Map.of("n", 1))).isEqualTo(1);
        assertThat(store.append("tasks", Map.of("n", 2))).isEqualTo(2);
        assertThat(store.append("other", Map.of("n", 1))).isEqualTo(1);   // own space

        List<Channel.Event<Map>> events = store.readAfter("tasks", 0, 10);
        assertThat(events).hasSize(2);
        assertThat(events.get(0).seq()).isEqualTo(1);
        assertThat(events.get(1).value().get("n")).isEqualTo(2);
        assertThat(store.readAfter("tasks", 1, 10)).hasSize(1);           // replay cursor
        assertThat(store.lastSeq("tasks")).isEqualTo(2);
        assertThat(store.lastSeq("unknown")).isZero();
    }
}
