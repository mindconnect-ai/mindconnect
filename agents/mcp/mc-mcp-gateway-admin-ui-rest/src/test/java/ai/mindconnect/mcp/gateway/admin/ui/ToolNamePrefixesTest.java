package ai.mindconnect.mcp.gateway.admin.ui;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ToolNamePrefixesTest {

    @Test
    void an_id_that_ends_where_the_tools_begin_loses_the_overlap() {
        // The case that prompted this: openbnb-airbnb offers airbnb_search,
        // and the naive answer was openbnb_airbnb_airbnb_search.
        assertThat(ToolNamePrefixes.suggest("openbnb-airbnb",
                List.of("airbnb_search", "airbnb_listing_details")))
                .isEqualTo("openbnb");
    }

    @Test
    void tools_without_a_common_prefix_leave_the_id_alone() {
        assertThat(ToolNamePrefixes.suggest("github-official",
                List.of("create_branch", "add_issue_comment", "list_commits")))
                .isEqualTo("github_official");
    }

    @Test
    void an_id_that_is_itself_the_tools_prefix_stays() {
        // Dropping it would leave nothing to tell two such servers apart.
        assertThat(ToolNamePrefixes.suggest("airbnb", List.of("airbnb_search", "airbnb_details")))
                .isEqualTo("airbnb");
    }

    @Test
    void a_single_tool_says_nothing_about_a_convention() {
        assertThat(ToolNamePrefixes.suggest("weather_service", List.of("weather_now")))
                .isEqualTo("weather_service");
    }

    @Test
    void one_tool_without_a_segment_breaks_the_rule_for_all() {
        assertThat(ToolNamePrefixes.suggest("files-mcp", List.of("files_read", "search")))
                .isEqualTo("files_mcp");
    }

    @Test
    void the_result_is_always_usable_in_a_tool_name() {
        assertThat(ToolNamePrefixes.suggest("Weird...Name!!", List.of())).isEqualTo("weird_name");
        assertThat(ToolNamePrefixes.suggest("--edges--", List.of())).isEqualTo("edges");
        assertThat(ToolNamePrefixes.suggest("", List.of())).isEmpty();
    }

    @Test
    void no_tool_names_at_all_is_just_the_id() {
        assertThat(ToolNamePrefixes.suggest("openbnb-airbnb", null)).isEqualTo("openbnb_airbnb");
    }
}
