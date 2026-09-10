package ai.mindconnect.mcp.proxy;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The check that runs before an MCP server is spawned. Without it a command
 * nobody can run costs thirty seconds of initialization timeout and a dropped
 * reactive error in the log; with it the caller is told in a sentence.
 */
class SdkMcpProxyCommandTest {

    @Test
    void aNameNobodyOnThePathAnswersToIsRefusedByName() {
        assertThatThrownBy(() -> SdkMcpProxy.requireExecutable("mc-definitely-not-installed"))
                .isInstanceOf(McpProxyException.class)
                .hasMessageContaining("mc-definitely-not-installed")
                .hasMessageContaining("PATH");
    }

    @Test
    void anAbsolutePathThatIsNotThereIsRefusedToo() {
        assertThatThrownBy(() -> SdkMcpProxy.requireExecutable("/opt/nothing/here/docker"))
                .isInstanceOf(McpProxyException.class)
                .hasMessageContaining("not executable");
    }

    @Test
    void anOrdinaryCommandPasses() {
        // "sh" is on the PATH of every machine this runs on, CI included.
        assertThatCode(() -> SdkMcpProxy.requireExecutable("sh")).doesNotThrowAnyException();
    }

    @Test
    void theCheckIsImmediate() {
        long start = System.currentTimeMillis();
        var spawn = new McpStdioSpawn("mc-definitely-not-installed", List.of("run"),
                Map.of(), Duration.ofSeconds(30), Duration.ofSeconds(60));

        assertThatThrownBy(() -> new SdkMcpProxy().connect(spawn))
                .isInstanceOf(McpProxyException.class);

        // The point of the check: no waiting for a startup timeout that can
        // never be met.
        assertThat(System.currentTimeMillis() - start).isLessThan(2_000L);
    }
}
