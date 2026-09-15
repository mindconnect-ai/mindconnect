package ai.mindconnect.adminui.service;

import ai.mindconnect.adminui.service.TaskMonitor.CancelResult;
import ai.mindconnect.adminui.service.TaskMonitor.Snapshot;
import ai.mindconnect.adminui.service.TaskMonitor.TaskView;
import ai.mindconnect.agent.runtime.adapter.repo.memory.InMemoryAgentDefinitionRepository;
import ai.mindconnect.agent.runtime.adapter.repo.memory.InMemoryAgentSessionRepository;
import ai.mindconnect.agent.runtime.domain.AgentDefinition;
import ai.mindconnect.agent.runtime.domain.AgentDefinitionStatus;
import ai.mindconnect.agent.runtime.domain.AgentSession;
import ai.mindconnect.agent.runtime.domain.SessionStatus;
import ai.mindconnect.agent.runtime.service.task.AgentTurnWorker;
import ai.mindconnect.agent.AgentId;
import ai.mindconnect.agent.SessionId;
import ai.mindconnect.agent.UserId;
import ai.mindconnect.message.domain.ConversationId;
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

    private static final AgentId AGENT_ID = AgentId.random();
    private static final SessionId ALICE_SESSION = SessionId.random();

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
        definitions.save(new AgentDefinition(AGENT_ID, "Scout", "A test agent",
                "assistants", "bot", "prompt", null, "cfg", 5, null,
                AgentDefinitionStatus.ACTIVE, List.of(), List.of(), null, null, null, null));
        var sessions = new InMemoryAgentSessionRepository();
        sessions.create(new AgentSession(ALICE_SESSION, AGENT_ID, UserId.of("alice"), ConversationId.random(),
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
                Map.of(AgentTurnWorker.SESSION_ID, ALICE_SESSION.value(), AgentTurnWorker.DEPTH, 0)));
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

    @Test
    void theBoardOfANamespaceShowsItsOwnTasksAndTheUnstampedOnes() throws Exception {
        String acme = queue.submit(TaskSubmission.of(AgentTurnWorker.TYPE, Map.of(
                AgentTurnWorker.SESSION_ID, ALICE_SESSION.value(), AgentTurnWorker.DEPTH, 0,
                ai.mindconnect.agent.runtime.service.task.ScopeTaskAdvisor.NAMESPACE, "acme")));
        String other = queue.submit(TaskSubmission.of(AgentTurnWorker.TYPE, Map.of(
                AgentTurnWorker.SESSION_ID, SessionId.random().value(), AgentTurnWorker.DEPTH, 0,
                ai.mindconnect.agent.runtime.service.task.ScopeTaskAdvisor.NAMESPACE, "other")));
        String unstamped = submitTurn();
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
        ai.mindconnect.agent.Namespace ns = new ai.mindconnect.agent.Namespace("acme");

        // A task between QUEUED and RUNNING is in neither list for an instant: wait until all three run.
        Snapshot full = monitor.snapshot();
        for (int i = 0; i < 100 && full.active().size() < 3; i++) {
            Thread.sleep(20);
            full = monitor.snapshot();
        }
        Snapshot board = full.in(ns);

        assertThat(board.active()).extracting(TaskView::id).containsExactlyInAnyOrder(acme, unstamped);
        assertThat(monitor.snapshot().active()).extracting(TaskView::id).contains(other);
        assertThat(monitor.counts(ns).running() + monitor.counts(ns).waiting()).isEqualTo(2);
        assertThat(monitor.counts().running() + monitor.counts().waiting()).isEqualTo(3);
        assertThat(board.active().stream().filter(v -> v.id().equals(acme)).findFirst().orElseThrow().namespace())
                .contains(ns);
    }
}
