package ai.mindconnect.adminui.service;

import ai.mindconnect.agent.scope.ScopeSupplier;
import ai.mindconnect.agent.SessionId;
import ai.mindconnect.agent.UserId;
import ai.mindconnect.taskqueue.TaskOutcome;
import ai.mindconnect.taskqueue.TaskSubmission;
import ai.mindconnect.taskqueue.local.LocalTaskQueue;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The wire side of the user's stream, through a capturing emitter: the
 * connection is committed before anything happens, a task transition lands
 * as a {@code patch} frame rendered for the attached user, and a user event
 * lands as a {@code user} frame — on that user's connection only.
 */
class UserStreamTest {

    private static final SessionId ALICE_SESSION = SessionId.random();

    private LocalTaskQueue queue;
    private TaskMonitor monitor;
    /** The sessions of the installation: what changes about one is what its user's stream tells. */
    private ai.mindconnect.agent.management.api.SessionManagement sessions;
    private UserStream stream;
    private final CountDownLatch started = new CountDownLatch(1);
    private final CountDownLatch release = new CountDownLatch(1);

    /** Records every write as the SSE text it would put on the wire. */
    private static final class CapturingEmitter extends SseEmitter {
        final List<String> frames = new CopyOnWriteArrayList<>();
        final CountDownLatch patchArrived = new CountDownLatch(1);
        final CountDownLatch userArrived = new CountDownLatch(1);

        CapturingEmitter() { super(0L); }

        @Override
        public void send(SseEventBuilder builder) {
            String frame = builder.build().stream()
                    .map(part -> String.valueOf(part.getData()))
                    .collect(java.util.stream.Collectors.joining());
            frames.add(frame);
            if (frame.contains("event:patch")) patchArrived.countDown();
            if (frame.contains("event:user")) userArrived.countDown();
        }
    }

    @BeforeEach
    void setUp() {
        queue = new LocalTaskQueue(new ai.mindconnect.taskqueue.memory.InMemoryTaskStore());
        queue.register("turn", ctx -> {
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
        monitor = new TaskMonitor(queue, TaskMonitorTest.runtimeWithAlicesSession(ALICE_SESSION,
                ai.mindconnect.agent.scope.ScopeSupplier.fixed(new ai.mindconnect.agent.scope.Namespace("test"))));
        var stored = new ai.mindconnect.agent.runtime.store.InMemorySessionRepository(ScopeSupplier.local());
        var messages = new ai.mindconnect.agent.runtime.store.InMemorySessionMessageRepository(ScopeSupplier.local(), java.time.Clock.systemUTC());
        sessions = new ai.mindconnect.agent.management.RepositorySessionManagement(stored,
                new ai.mindconnect.agent.runtime.store.DefaultSessionStore(stored, messages),
                new ai.mindconnect.agent.management.adapter.InMemoryAgentRepository(ScopeSupplier.local()),
                ai.mindconnect.agent.management.api.SessionDirectoryPolicy.unrestricted(), List.of(),
                new ai.mindconnect.agent.management.SessionLifecycle(stored, session -> Optional.empty()));
        stream = new UserStream(Optional.of(monitor), sessions, new ObjectMapper());
    }

    @AfterEach
    void tearDown() {
        release.countDown();
        stream.shutdown();
        monitor.shutdown();
        queue.close();
    }

    @Test
    void theConnectionIsCommittedBeforeAnythingHappens() {
        var emitter = new CapturingEmitter();
        stream.attach(emitter, "alice");

        assertThat(emitter.frames).hasSize(1);
        assertThat(emitter.frames.get(0)).startsWith(":attached");
        assertThat(stream.subscriberCount()).isEqualTo(1);

        stream.detach(emitter);
        assertThat(stream.subscriberCount()).isZero();
    }

    @Test
    void aTaskTransitionBecomesAPatchFrameRenderedForTheAttachedUser() throws Exception {
        var emitter = new CapturingEmitter();
        stream.attach(emitter, "alice");

        String id = queue.submit(TaskSubmission.of("turn",
                Map.of("sessionId", ALICE_SESSION.value(), "agent", Map.of("name", "Scout"))));
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(emitter.patchArrived.await(5, TimeUnit.SECONDS)).isTrue();

        String frame = emitter.frames.stream().filter(f -> f.contains("event:patch")).findFirst().orElseThrow();
        assertThat(frame).contains("\"targetId\":\"" + TaskMonitor.CHANNEL_ID + "\"");
        assertThat(frame).contains("task-" + id);
        // rendered for alice: her task carries its Cancel
        assertThat(frame).contains("/admin/api/tasks/" + id + "/cancel");
    }

    @Test
    void theBoardFollowsTheNamespaceTheViewerMayStillWorkIn() throws Exception {
        var remembered = new java.util.concurrent.atomic.AtomicReference<>(Optional.of(new ai.mindconnect.agent.scope.Namespace("acme")));
        var namespaced = new UserStream(Optional.of(monitor), sessions, new ObjectMapper(), user -> remembered.get());
        try {
            var emitter = new CapturingEmitter();
            namespaced.attach(emitter, "alice", ai.mindconnect.agent.scope.Namespace.DEFAULT);

            String acme = queue.submit(TaskSubmission.of("turn", Map.of(
                    "sessionId", ALICE_SESSION.value(), "agent", Map.of("name", "Scout"),
                    "namespace", "acme")));
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(emitter.patchArrived.await(5, TimeUnit.SECONDS)).isTrue();
            String frame = emitter.frames.stream().filter(f -> f.contains("event:patch")).reduce((a, b) -> b).orElseThrow();
            assertThat(frame).as("in acme, alice sees her acme task").contains("task-" + acme);

            // she was removed from acme: the remembered choice no longer counts, the board is the default namespace's
            remembered.set(Optional.empty());
            emitter.frames.clear();
            String other = queue.submit(TaskSubmission.of("turn", Map.of(
                    "sessionId", ALICE_SESSION.value(), "agent", Map.of("name", "Scout"),
                    "namespace", "other")));
            waitForFrames(emitter, 1);
            String next = emitter.frames.stream().filter(f -> f.contains("event:patch")).reduce((a, b) -> b).orElseThrow();
            assertThat(next).doesNotContain("task-" + acme).doesNotContain("task-" + other);
        } finally {
            namespaced.shutdown();
        }
    }

    private static void waitForFrames(CapturingEmitter emitter, int count) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (emitter.frames.stream().filter(f -> f.contains("event:patch")).count() < count
                && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
    }

    @Test
    void aUserEventBecomesAUserFrameOnThatUsersConnectionOnly() throws Exception {
        var alice = new CapturingEmitter();
        var bob = new CapturingEmitter();
        stream.attach(alice, "alice");
        stream.attach(bob, "bob");

        ai.mindconnect.agent.runtime.api.domain.AgentDefinition helper =
                new ai.mindconnect.agent.runtime.api.domain.AgentDefinition("helper", "Helps.", "You help.",
                        ai.mindconnect.agent.runtime.api.domain.ModelSettings.NONE, List.of(), 5, Map.of());
        SessionId opened = sessions.open(UserId.of("alice"), null,
                new ai.mindconnect.agent.management.api.SessionAgent.Own(helper, null), null,
                ai.mindconnect.agent.management.api.SessionDirectories.NONE).id();
        assertThat(alice.userArrived.await(5, TimeUnit.SECONDS)).isTrue();

        String frame = alice.frames.stream().filter(f -> f.contains("event:user")).findFirst().orElseThrow();
        assertThat(frame).contains("\"type\":\"session_started\"");
        assertThat(frame).contains("\"sessionId\":\"" + opened.value() + "\"");
        assertThat(frame).contains("\"sessionType\":\"chat\"");
        assertThat(bob.frames).noneMatch(f -> f.contains("event:user"));
    }
}
