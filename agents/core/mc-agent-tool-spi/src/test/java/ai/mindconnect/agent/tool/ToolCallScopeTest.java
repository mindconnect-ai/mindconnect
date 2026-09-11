package ai.mindconnect.agent.tool;

import ai.mindconnect.agent.AgentId;
import ai.mindconnect.agent.SessionId;
import ai.mindconnect.agent.UserId;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The session's part of a scope, filled in per agent tool, working directory and all. */
class ToolCallScopeTest {

    @Test
    void theSessionScopeCarriesTheWorkingDirectoryToEveryAgentsTool() {
        UserId user = UserId.of("u");
        SessionId session = SessionId.random();
        AgentId agent = AgentId.random();
        ToolCallScope base = ToolCallScope.ofSession(user, session, "/work", List.of("/lib"));

        assertThat(base.agentId()).isNull();
        ToolCallScope scoped = base.forAgent(agent);
        assertThat(scoped).isEqualTo(new ToolCallScope(user, session, agent, "/work", List.of("/lib")));
        assertThat(scoped.hasWorkingDir()).isTrue();
    }

    @Test
    void theThreeArgumentScopeHasNoWorkingDirectory() {
        ToolCallScope scope = new ToolCallScope(UserId.of("u"), SessionId.random(), AgentId.random());
        assertThat(scope.workingDir()).isNull();
        assertThat(scope.hasWorkingDir()).isFalse();
        assertThat(scope.additionalDirs()).isEmpty();
        assertThat(ToolCallScope.ofSession(UserId.of("u"), SessionId.random(), " ").hasWorkingDir()).isFalse();
    }

    @Test
    void aScopeCanBeBoundToTheRunningThread_andItsChildren() throws Exception {
        ToolCallScope scope = ToolCallScope.ofSession(UserId.of("u"), SessionId.random(), "/work");
        assertThat(ToolCallScope.current()).isEmpty();

        String seen = scope.runWith(() -> {
            assertThat(ToolCallScope.current()).contains(scope);
            // A thread started inside sees the scope too — a workflow engine
            // that fans out still resolves its tools in the session's scope.
            var fromChild = new java.util.concurrent.atomic.AtomicReference<ToolCallScope>();
            Thread child = new Thread(() -> fromChild.set(ToolCallScope.current().orElse(null)));
            child.start();
            try { child.join(); } catch (InterruptedException e) { throw new RuntimeException(e); }
            assertThat(fromChild.get()).isEqualTo(scope);
            return "ran";
        });
        assertThat(seen).isEqualTo("ran");
        assertThat(ToolCallScope.current()).as("restored afterwards").isEmpty();
    }
}
