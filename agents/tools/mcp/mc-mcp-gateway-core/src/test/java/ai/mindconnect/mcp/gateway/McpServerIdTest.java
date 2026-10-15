package ai.mindconnect.mcp.gateway;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** A server id follows the rule of every other id, so that it is a safe file name and a stable key. */
class McpServerIdTest {

    @Test
    void the_ids_in_use_stay_valid() {
        for (String value : List.of("gmail", "playground-files", "openbnb-airbnb", "everything",
                "ui-demo", "github-official")) {
            assertThat(McpServerId.of(value).value()).isEqualTo(value);
        }
    }

    @Test
    void it_prints_as_its_value() {
        assertThat(McpServerId.of("github")).hasToString("github");
    }

    @Test
    void a_random_id_is_a_valid_one_and_a_fresh_one() {
        McpServerId one = McpServerId.random();

        assertThat(McpServerId.of(one.value())).isEqualTo(one);
        assertThat(McpServerId.random()).isNotEqualTo(one);
    }

    @Test
    void a_value_that_could_not_be_a_file_name_or_a_stable_key_is_refused() {
        // Upper case above all: a developer's file system treats GitHub and
        // github as one file, Postgres does not.
        for (String bad : List.of("GitHub", ".hidden", "-dash-first", "", "with space", "a/b",
                "x".repeat(129))) {
            assertThatThrownBy(() -> McpServerId.of(bad))
                    .as("'%s'", bad)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("not a valid id");
        }
        assertThatThrownBy(() -> McpServerId.of(null)).isInstanceOf(IllegalArgumentException.class);
        assertThat(McpServerId.of("a".repeat(128)).value()).hasSize(128);
    }
}
