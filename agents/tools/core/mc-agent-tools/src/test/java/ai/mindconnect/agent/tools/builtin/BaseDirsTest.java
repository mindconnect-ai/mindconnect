package ai.mindconnect.agent.tools.builtin;

import ai.mindconnect.agent.tool.AgentTool;
import ai.mindconnect.agent.tool.ToolCallScope;
import ai.mindconnect.agent.AgentId;
import ai.mindconnect.agent.SessionId;
import ai.mindconnect.agent.UserId;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Where a file-rooted tool works: the session's working directory first —
 * it is where the user is — then the tool's own override, then the
 * runtime default, then home.
 */
class BaseDirsTest {

    private static final AgentId AGENT = AgentId.random();
    private static final AgentTool PLAIN = AgentTool.of("file_read");
    private static final AgentTool PINNED = AgentTool.of("file_read", null, Map.of("baseDir", "/pinned"));

    private static ToolCallScope scope(String workingDir) {
        return ToolCallScope.ofSession(UserId.of("u"), SessionId.random(), workingDir).forAgent(AGENT);
    }

    @Test
    void theSessionsWorkingDirectoryWins() {
        assertThat(BaseDirs.resolve(scope("/home/me/src/app"), PLAIN, "/default")).isEqualTo("/home/me/src/app");
        assertThat(BaseDirs.resolve(scope("/home/me/src/app"), PINNED, "/default"))
                .as("even over the tool's own override — the user chose the directory for this chat")
                .isEqualTo("/home/me/src/app");
    }

    @Test
    void theRootsCarryTheAdditionalDirectories() {
        ToolCallScope scope = ToolCallScope.ofSession(UserId.of("u"), SessionId.random(),
                "/home/me/src/app", java.util.List.of("/home/me/lib")).forAgent(AGENT);

        var roots = BaseDirs.roots(scope, PINNED, "/default");
        assertThat(roots.base()).isEqualTo(java.nio.file.Path.of("/home/me/src/app"));
        assertThat(roots.extra()).containsExactly(java.nio.file.Path.of("/home/me/lib"));

        var fallback = BaseDirs.roots(scope(null), PINNED, "/default");
        assertThat(fallback.base()).isEqualTo(java.nio.file.Path.of("/pinned"));
        assertThat(fallback.extra()).isEmpty();
        assertThat(BaseDirs.roots(null, PLAIN, "/default").base()).isEqualTo(java.nio.file.Path.of("/default"));
    }

    @Test
    void withoutOneTheOverrideThenTheDefaultThenHome() {
        assertThat(BaseDirs.resolve(scope(null), PINNED, "/default")).isEqualTo("/pinned");
        assertThat(BaseDirs.resolve(scope(null), PLAIN, "/default")).isEqualTo("/default");
        assertThat(BaseDirs.resolve(scope(""), PLAIN, "")).isEqualTo(System.getProperty("user.home"));
        assertThat(BaseDirs.resolve(null, PLAIN, "/default")).as("no scope at all").isEqualTo("/default");
    }
}
