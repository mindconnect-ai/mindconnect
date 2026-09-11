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
}
