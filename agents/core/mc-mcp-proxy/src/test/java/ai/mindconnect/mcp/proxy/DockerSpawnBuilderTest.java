package ai.mindconnect.mcp.proxy;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DockerSpawnBuilderTest {

    @Test
    void minimal_spawn_has_run_minus_i_rm_and_image() {
        McpStdioSpawn s = DockerSpawnBuilder.of("foo/bar:latest").build();

        assertThat(s.command()).isEqualTo("docker");
        assertThat(s.args()).containsExactly("run", "-i", "--rm", "foo/bar:latest");
        assertThat(s.env()).isEmpty();
    }

    @Test
    void mount_adds_dash_v_with_host_colon_container() {
        McpStdioSpawn s = DockerSpawnBuilder.of("img")
                .mount("/host/path", "/container/path")
                .build();

        assertThat(s.args()).containsSubsequence("-v", "/host/path:/container/path", "img");
    }

    @Test
    void env_adds_dash_e_per_var_and_keeps_order() {
        McpStdioSpawn s = DockerSpawnBuilder.of("img")
                .env("FOO", "1")
                .env("BAR", "2")
                .build();

        // -e flags come before image, in insertion order
        assertThat(s.args()).containsSubsequence("-e", "FOO=1", "-e", "BAR=2", "img");
    }

    @Test
    void command_override_is_appended_after_image() {
        McpStdioSpawn s = DockerSpawnBuilder.of("img")
                .commandOverride(List.of("--debug", "--mode=foo"))
                .build();

        assertThat(s.args()).endsWith("img", "--debug", "--mode=foo");
    }

    @Test
    void custom_timeouts_are_propagated() {
        McpStdioSpawn s = DockerSpawnBuilder.of("img")
                .startupTimeout(Duration.ofSeconds(10))
                .callTimeout(Duration.ofSeconds(20))
                .build();

        assertThat(s.startupTimeout()).isEqualTo(Duration.ofSeconds(10));
        assertThat(s.callTimeout()).isEqualTo(Duration.ofSeconds(20));
    }

    @Test
    void blank_image_is_rejected() {
        try {
            DockerSpawnBuilder.of("");
            assertThat(false).as("expected IllegalArgumentException").isTrue();
        } catch (IllegalArgumentException expected) { /* ok */ }
    }
}
