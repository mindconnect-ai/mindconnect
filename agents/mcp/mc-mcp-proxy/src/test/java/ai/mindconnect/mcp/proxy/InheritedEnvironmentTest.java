package ai.mindconnect.mcp.proxy;

import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapperSupplier;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A started MCP server is somebody else's code. It gets what a command needs
 * to run, and not the secrets this process was started with.
 */
class InheritedEnvironmentTest {

    @Test
    void the_secrets_of_this_process_stay_behind() {
        Map<String, String> environment = new HashMap<>(Map.of(
                "PATH", "/usr/bin",
                "HOME", "/home/mc",
                "MC_POSTGRES_PASSWORD", "s3cret",
                "MINDCONNECT_ENCRYPTION_SECRET_KEY", "0123456789abcdef",
                "OPENAI_API_KEY", "sk-test",
                "LC_ALL", "de_CH.UTF-8",
                "https_proxy", "http://proxy:3128",
                "DOCKER_HOST", "unix:///run/podman/podman.sock"));

        InheritedEnvironment.trim(environment);

        assertThat(environment).containsOnlyKeys("PATH", "HOME", "LC_ALL", "https_proxy", "DOCKER_HOST");
    }

    @Test
    void windows_spellings_count_as_the_same_name() {
        assertThat(InheritedEnvironment.kept("Path")).isTrue();
        assertThat(InheritedEnvironment.kept("SystemRoot")).isTrue();
        assertThat(InheritedEnvironment.kept("ProgramFiles(x86)")).isTrue();
        assertThat(InheritedEnvironment.kept("Tavily_Api_Key")).isFalse();
    }

    @Test
    void the_stdio_transport_starts_its_process_with_the_trimmed_environment() {
        // On the map the JDK hands out, not a copy: that is the one the process
        // starts with, and removing from it has to work.
        ProcessBuilder builder = new SdkMcpProxy.TrimmedStdioClientTransport(
                ServerParameters.builder("sh").build(), new JacksonMcpJsonMapperSupplier().get())
                .getProcessBuilder();

        assertThat(builder.environment().keySet()).allMatch(InheritedEnvironment::kept);
        if (System.getenv("PATH") != null) {
            assertThat(builder.environment()).containsKey("PATH");
        }
    }
}
