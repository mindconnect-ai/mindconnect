package ai.mindconnect.adminui.service;

import ai.mindconnect.agent.SessionId;
import ai.mindconnect.agent.tool.AgentTool;
import ai.mindconnect.agent.tool.Tool;
import ai.mindconnect.agent.tool.ToolCallScope;
import ai.mindconnect.agent.tool.ToolRegistry;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every test runs in a session of its own. What a tool holds for that session
 * — an MCP server's pooled connection, the container behind it — has to go
 * when the test is over, or every click leaves one behind.
 */
class ToolTestServiceTest {

    @Test
    void the_test_session_is_released_after_the_call() {
        RecordingRegistry registry = new RecordingRegistry(arguments -> "ran");

        ToolTestService.Result result = new ToolTestService(registry).test(AgentTool.of("mcp_tool"), "{}");

        assertThat(result.ok()).isTrue();
        assertThat(registry.released).containsExactly(registry.resolvedIn.sessionId());
    }

    @Test
    void a_tool_that_throws_still_has_its_session_released() {
        RecordingRegistry registry = new RecordingRegistry(arguments -> {
            throw new IllegalStateException("container did not start");
        });

        ToolTestService.Result result = new ToolTestService(registry).test(AgentTool.of("mcp_tool"), "{}");

        assertThat(result.ok()).isFalse();
        assertThat(registry.released).containsExactly(registry.resolvedIn.sessionId());
    }

    /** Hands out one tool that behaves as told, and records the sessions. */
    private static final class RecordingRegistry implements ToolRegistry {

        private final Function<Map<String, Object>, String> behaviour;
        ToolCallScope resolvedIn;
        final List<SessionId> released = new ArrayList<>();

        RecordingRegistry(Function<Map<String, Object>, String> behaviour) {
            this.behaviour = behaviour;
        }

        @Override
        public Optional<Tool> resolve(AgentTool agentTool, ToolCallScope scope) {
            resolvedIn = scope;
            return Optional.of(new Tool() {
                @Override public String name() { return agentTool.name(); }
                @Override public String description() { return "a test tool"; }
                @Override public Map<String, Object> parametersSchema() { return Map.of("type", "object"); }
                @Override public String execute(Map<String, Object> arguments) { return behaviour.apply(arguments); }
            });
        }

        @Override
        public void releaseSession(SessionId sessionId) {
            released.add(sessionId);
        }
    }
}
