package ai.mindconnect.mcp.gateway.local;

import org.junit.jupiter.api.Test;
import org.springframework.core.env.AbstractEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.core.env.MapPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class McpStartPolicyTest {

    @Test
    void without_sign_in_both_kinds_of_server_start() {
        assertThat(McpStartPolicy.from(environment(Map.of())))
                .isEqualTo(new McpStartPolicy(true, true));
        assertThat(McpStartPolicy.from(environment(Map.of("mindconnect.auth.enabled", "false"))))
                .isEqualTo(new McpStartPolicy(true, true));
    }

    @Test
    void with_sign_in_neither_starts_until_an_operator_allows_it() {
        // Registering asks for a login, not an admin role: with sign-in on,
        // "may start a process" would otherwise mean "has an account".
        assertThat(McpStartPolicy.from(environment(Map.of("mindconnect.auth.enabled", "true"))))
                .isEqualTo(new McpStartPolicy(false, false));
        assertThat(McpStartPolicy.from(environment(Map.of(
                "mindconnect.auth.enabled", "true", "mindconnect.mcp.allow-process", "true"))))
                .isEqualTo(new McpStartPolicy(true, false));
        assertThat(McpStartPolicy.from(environment(Map.of(
                "mindconnect.auth.enabled", "true", "mindconnect.mcp.allow-docker", "true"))))
                .isEqualTo(new McpStartPolicy(false, true));
    }

    @Test
    void an_explicit_no_holds_without_sign_in_too() {
        assertThat(McpStartPolicy.from(environment(Map.of("mindconnect.mcp.allow-process", "false"))))
                .isEqualTo(new McpStartPolicy(false, true));
    }

    /** Only the given properties — not the environment of whoever runs the test. */
    private static Environment environment(Map<String, Object> properties) {
        AbstractEnvironment environment = new AbstractEnvironment() { };
        environment.getPropertySources().addFirst(new MapPropertySource("test", properties));
        return environment;
    }
}
