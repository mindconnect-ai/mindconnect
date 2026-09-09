package ai.mindconnect.adminui.service;

import ai.mindconnect.agent.adapter.repo.memory.InMemoryAgentDefinitionRepository;
import ai.mindconnect.agent.adapter.repo.memory.InMemoryAgentSessionRepository;
import ai.mindconnect.agent.domain.AgentDefinition;
import ai.mindconnect.agent.domain.AgentDefinitionStatus;
import ai.mindconnect.agent.domain.AgentSession;
import ai.mindconnect.agent.domain.SessionStatus;
import ai.mindconnect.agent.service.stream.UserChannels;
import ai.mindconnect.agent.service.stream.UserEvent;
import ai.mindconnect.agent.service.task.AgentTurnWorker;
import ai.mindconnect.common.Namespace;
import ai.mindconnect.taskqueue.TaskOutcome;
import ai.mindconnect.taskqueue.TaskSubmission;
import ai.mindconnect.taskqueue.local.LocalTaskQueue;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
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

    private static final Namespace NS = new Namespace("local");
    private static final UUID AGENT_ID = UUID.randomUUID();
    private static final UUID ALICE_SESSION = UUID.randomUUID();

    private LocalTaskQueue queue;
    private TaskMonitor monitor;
    private UserChannels userChannels;
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
        userChannels = new UserChannels();
        stream = new UserStream(Optional.of(monitor), userChannels, new ObjectMapper());
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

        String id = queue.submit(TaskSubmission.of(AgentTurnWorker.TYPE,
                Map.of(AgentTurnWorker.SESSION_ID, ALICE_SESSION.toString(), AgentTurnWorker.DEPTH, 0)));
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(emitter.patchArrived.await(5, TimeUnit.SECONDS)).isTrue();

        String frame = emitter.frames.stream().filter(f -> f.contains("event:patch")).findFirst().orElseThrow();
        assertThat(frame).contains("\"targetId\":\"" + TaskMonitor.CHANNEL_ID + "\"");
        assertThat(frame).contains("task-" + id);
        // rendered for alice: her task carries its Cancel
        assertThat(frame).contains("/admin/api/tasks/" + id + "/cancel");
    }

    @Test
    void aUserEventBecomesAUserFrameOnThatUsersConnectionOnly() throws Exception {
        var alice = new CapturingEmitter();
        var bob = new CapturingEmitter();
        stream.attach(alice, "alice");
        stream.attach(bob, "bob");

        UUID turn = UUID.randomUUID();
        userChannels.publish("alice", new UserEvent.TurnStarted(ALICE_SESSION, turn));
        assertThat(alice.userArrived.await(5, TimeUnit.SECONDS)).isTrue();

        String frame = alice.frames.stream().filter(f -> f.contains("event:user")).findFirst().orElseThrow();
        assertThat(frame).contains("\"type\":\"turn_started\"");
        assertThat(frame).contains("\"sessionId\":\"" + ALICE_SESSION + "\"");
        assertThat(frame).contains("\"turnId\":\"" + turn + "\"");
        assertThat(bob.frames).noneMatch(f -> f.contains("event:user"));
    }
}
