package ai.mindconnect.agent.tools.virtualenv;

import ai.mindconnect.agent.SessionId;
import ai.mindconnect.agent.UserId;
import ai.mindconnect.agent.tool.ToolCallScope;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Nothing tells the provider when a chat is over, so what it keeps per workspace
 * goes once the workspace was not used for the idle timeout — and not before.
 */
class VirtualEnvWorkspaceProviderIdleTest {

    private static final Duration IDLE = Duration.ofHours(2);

    /** A clock the test moves forward. */
    private static final class TestClock extends Clock {
        private Instant now = Instant.parse("2026-09-23T10:00:00Z");

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    /** An environment that is running at once; counts the acquires. */
    private static final class RunningClient extends VirtualEnvClient {
        final AtomicInteger acquires = new AtomicInteger();

        RunningClient() {
            super(URI.create("http://virtual-env.invalid"), TokenSource.none(), Duration.ofSeconds(1));
        }

        @Override
        public Environment acquire(WorkspaceKey key) {
            acquires.incrementAndGet();
            return new Environment("env-" + key.sessionKey(), "RUNNING", 0, null);
        }

        @Override
        public ExecResult exec(WorkspaceKey key, String id, String command, String stdin, Map<String, String> env,
                               long timeoutSeconds) {
            return new ExecResult(0, "", false, false, 1);
        }
    }

    private final TestClock clock = new TestClock();
    private final RunningClient client = new RunningClient();
    private final VirtualEnvWorkspaceProvider provider =
            new VirtualEnvWorkspaceProvider(client, "python", Duration.ofMinutes(1), IDLE, clock);

    @Test
    void an_idle_workspace_is_forgotten_and_a_used_one_is_kept() throws Exception {
        run("chat-a");
        run("chat-b");
        assertThat(provider.remembered()).isEqualTo(2);

        clock.advance(Duration.ofHours(1));
        run("chat-b");                                 // chat-b stays in use
        clock.advance(Duration.ofMinutes(61));         // chat-a idle 2h01m, chat-b 1h01m
        run("chat-c");                                 // any use sweeps

        assertThat(provider.remembered()).as("chat-a is forgotten, chat-b and chat-c are kept").isEqualTo(2);
        int before = client.acquires.get();
        run("chat-b");
        assertThat(client.acquires.get()).as("chat-b still knows its environment").isEqualTo(before);
        run("chat-a");
        assertThat(client.acquires.get()).as("chat-a acquires its environment again").isEqualTo(before + 1);
    }

    @Test
    void nothing_is_forgotten_before_the_idle_timeout() throws Exception {
        run("chat-a");
        clock.advance(IDLE.minusMinutes(1));
        run("chat-b");

        assertThat(provider.remembered()).isEqualTo(2);
    }

    @Test
    void the_idle_timeout_must_be_positive() {
        assertThatThrownBy(() -> new VirtualEnvWorkspaceProvider(client, "python", Duration.ofMinutes(1),
                Duration.ZERO, clock)).isInstanceOf(IllegalArgumentException.class);
    }

    /** One command in the chat's workspace, which remembers the environment's id. */
    private void run(String chat) throws Exception {
        ToolCallScope scope = new ToolCallScope(UserId.of("u"), SessionId.of(chat), null);
        provider.commands(scope, null).orElseThrow().run("true", null, Map.of(), Duration.ofSeconds(5));
    }
}
