package ai.mindconnect.mcp.gateway;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The prefix is not a label. It becomes the head of every tool name this
 * server offers, and that name travels to the model provider inside a
 * request carrying the agent's other tools too — so one bad character costs
 * the whole turn, not this one capability.
 */
class McpServerRegistrationTest {

    private static McpServerRegistration with(String prefix) {
        return new McpServerRegistration(McpServerId.of("gmail"), "Gmail", null, true,
                prefix, new McpTarget.Docker("mcp/gmail", List.of(), Map.of(), List.of(), List.of()),
                null);
    }

    @Test
    void a_prefix_that_would_break_the_tool_name_is_refused() {
        // "My Gmail" + "_" + "search_emails" is not a legal tool name for
        // either OpenAI or Anthropic, and the registration used to save fine
        // and fail much later, in every request the agent made.
        assertThatThrownBy(() -> with("My Gmail"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("toolNamePrefix");

        assertThatThrownBy(() -> with("gmail.v2")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> with("gmail/v2")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> with("ä")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> with("x".repeat(33))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void the_prefixes_in_use_stay_valid() {
        // Exactly what is registered today — the check must not invalidate
        // anyone's store.
        for (String prefix : List.of("gmail", "ev", "openbnb_airbnb", "pgfiles", "uidemo",
                "github-official", "A1")) {
            assertThat(with(prefix).toolNamePrefix()).isEqualTo(prefix);
        }
    }

    @Test
    void a_blank_prefix_still_says_what_is_missing() {
        assertThatThrownBy(() -> with("  "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("required");
    }

    @Test
    void a_display_name_falls_back_to_the_id() {
        assertThat(with("gmail").displayName()).isEqualTo("Gmail");
        assertThat(new McpServerRegistration(McpServerId.of("gmail"), "  ", null, true, "gmail",
                new McpTarget.Docker("mcp/gmail", List.of(), Map.of(), List.of(), List.of()), null)
                .displayName()).isEqualTo("gmail");
    }
}
