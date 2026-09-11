package ai.mindconnect.adminui.service;

import ai.mindconnect.adminui.service.TaskMonitor.CancelResult;
import ai.mindconnect.adminui.service.TaskMonitor.Snapshot;
import ai.mindconnect.adminui.service.TaskMonitor.TaskView;
import ai.mindconnect.agent.adapter.repo.memory.InMemoryAgentDefinitionRepository;
import ai.mindconnect.agent.adapter.repo.memory.InMemoryAgentSessionRepository;
import ai.mindconnect.agent.domain.AgentDefinition;
import ai.mindconnect.agent.domain.AgentDefinitionStatus;
import ai.mindconnect.agent.domain.AgentSession;
import ai.mindconnect.agent.domain.SessionStatus;
import ai.mindconnect.agent.service.task.AgentTurnWorker;
import ai.mindconnect.agent.Namespace;
import ai.mindconnect.taskqueue.TaskOutcome;
import ai.mindconnect.taskqueue.TaskStatus;
import ai.mindconnect.taskqueue.TaskSubmission;
import ai.mindconnect.taskqueue.local.LocalTaskQueue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The monitor against a real {@link LocalTaskQueue}: a task that runs for
 * someone shows up under their name, only they may cancel it, and the
 * cancel reaches the worker. A transition on the queue ends as one snapshot
 * for every subscriber; the wire side of that is {@link UserStreamTest}'s.
 */
class TaskMonitorTest {

    private static final Namespace NS = new Namespace("local");
    private static final UUID AGENT_ID = UUID.randomUUID();
    private static final UUID ALICE_SESSION = UUID.randomUUID();

    private LocalTaskQueue queue;
    private TaskMonitor monitor;
    private final CountDownLatch started = new CountDownLatch(1);
    private final CountDownLatch release = new CountDownLatch(1);

    @BeforeEach
    void setUp() {
        queue = new LocalTaskQueue(new ai.mindconnect.taskqueue.memory.InMemoryTaskStore());
        // A stand-in for the agent turn: blocks until released or cancelled,
        // so the task is observably RUNNING while the test looks at it.
        queue.register(AgentTurnWorker.TYPE, ctx -> {
            started.countDown();
            try {
                while (!ctx.cancelRequested() && !release.await(20, TimeUnit.MILLISECONDS)) {
                    // spin on the cooperative flag
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return TaskOutcome.done("ok");
        });

        var definitions = new InMemoryAgentDefinitionRepository();
        definitions.save(new AgentDefinition(AGENT_ID, NS, "Scout", "A test agent",
                "assistants", "bot", "prompt", null, "cfg", 5, null,
                AgentDefinitionStatus.ACTIVE, List.of(), List.of(), null, null, null, null));
        var sessions = new InMemoryAgentSessionRepository();
        sessions.save(new AgentSession(ALICE_SESSION, AGENT_ID, NS, "alice", UUID.randomUUID(),
                "Alice asks", SessionStatus.ACTIVE, Instant.now(), null,
                null, null, null, null, null, null, null));

        monitor = new TaskMonitor(queue, sessions, definitions);
    }

    @AfterEach
    void tearDown() {
        release.countDown();
        monitor.shutdown();
        queue.close();
    }

    private String submitTurn() {
        return queue.submit(TaskSubmission.of(AgentTurnWorker.TYPE,
                Map.of(AgentTurnWorker.SESSION_ID, ALICE_SESSION.toString(), AgentTurnWorker.DEPTH, 0)));
    }

    @Test
    void aRunningTurnIsListedUnderItsAgentAndOwner() throws Exception {
        String id = submitTurn();
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();

        Snapshot snapshot = monitor.snapshot();
        assertThat(snapshot.active()).extracting(TaskView::id).containsExactly(id);
        TaskView view = snapshot.active().get(0);
        assertThat(view.status()).isEqualTo(TaskStatus.RUNNING);
        assertThat(view.label()).isEqualTo("Scout");
        assertThat(view.owner()).isEqualTo("alice");
        assertThat(view.detail()).contains("turn").contains("Alice asks");
        assertThat(monitor.counts().running()).isEqualTo(1);
        assertThat(monitor.counts().busy()).isTrue();
    }

    @Test
    void onlyTheOwnerCancelsAndTheCancelReachesTheWorker() throws Exception {
        String id = submitTurn();
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();

        assertThat(monitor.cancel(id, "bob")).isEqualTo(CancelResult.NOT_OWNER);
        assertThat(queue.get(id).orElseThrow().status()).isEqualTo(TaskStatus.RUNNING);

        assertThat(monitor.cancel(id, "alice")).isEqualTo(CancelResult.CANCELLED);
        assertThat(queue.await(id, Duration.ofSeconds(5)).status()).isEqualTo(TaskStatus.CANCELLED);

        assertThat(monitor.cancel(id, "alice")).isEqualTo(CancelResult.ALREADY_FINISHED);
        assertThat(monitor.cancel("no-such-task", "alice")).isEqualTo(CancelResult.UNKNOWN);
        assertThat(monitor.snapshot().recent()).extracting(TaskView::id).containsExactly(id);
        assertThat(monitor.counts().busy()).isFalse();
    }

    @Test
    void aTransitionBecomesOneSnapshotForEverySubscriber() throws Exception {
        var snapshots = new CopyOnWriteArrayList<Snapshot>();
        var arrived = new CountDownLatch(1);
        var subscription = monitor.subscribe(event -> {
            snapshots.add(event.value());
            arrived.countDown();
        });

        String id = submitTurn();
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(arrived.await(5, TimeUnit.SECONDS))
                .as("the submit/start burst is debounced into a snapshot")
                .isTrue();

        assertThat(snapshots.get(0).active()).extracting(TaskView::id).containsExactly(id);
        subscription.close();
    }
}
