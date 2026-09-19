package ai.mindconnect.adminui.ui;

import ai.mindconnect.ui.model.UiPage;
import ai.mindconnect.ui.model.UiStack;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A module's screen gets into the sidebar through {@link AdminMenuContribution}.
 * The layout only places what it is handed: after the shipped sections, before
 * the Install group, and — for a chat-only viewer — right after the chat.
 * Whether a viewer may open an entry is the contribution's call, made before
 * the layout ever sees it.
 */
class AdminLayoutContributedNavTest {

    private static final AdminMenuContribution.Entry USAGE =
            AdminMenuContribution.Entry.of("nav-usage", "Usage", "/admin/usage", "chart");

    private static String menuJson(boolean chatOnly, String navigate, List<AdminMenuContribution.Entry> entries)
            throws Exception {
        AdminLayout layout = new AdminLayout("mc_user", false, "0.8.3", null, null, false)
                .chatOnly(chatOnly)
                .contributions(entries);
        UiPage page = UiPage.of(navigate, UiStack.of("body"));
        return new ObjectMapper().writeValueAsString(layout.withLayout(page));
    }

    @Test
    void a_contributed_entry_follows_the_shipped_sections_and_precedes_install() throws Exception {
        String json = menuJson(false, "/admin/agents", List.of(USAGE));

        assertThat(json).contains("nav-usage").contains("/admin/usage");
        assertThat(json.indexOf("nav-vector-stores")).isLessThan(json.indexOf("nav-usage"));
        assertThat(json.indexOf("nav-usage")).isLessThan(json.indexOf("nav-install"));
    }

    @Test
    void the_entry_is_selected_while_its_section_is_open() throws Exception {
        String json = menuJson(false, "/admin/usage?days=7", List.of(USAGE));

        // The selected flag sits on the usage item and on no other section.
        assertThat(json).contains("\"id\":\"nav-usage\"");
        assertThat(selectedIds(json)).containsExactly("nav-usage");
    }

    @Test
    void without_contributions_the_menu_is_as_before() throws Exception {
        assertThat(menuJson(false, "/admin/agents", List.of())).doesNotContain("nav-usage");
    }

    @Test
    void a_chat_only_viewer_sees_what_the_contribution_gave_for_them() throws Exception {
        String json = menuJson(true, "/chat", List.of(
                AdminMenuContribution.Entry.of("nav-my-usage", "My usage", "/admin/profile/usage", "chart")));

        assertThat(json).contains("nav-chat").contains("nav-my-usage");
        assertThat(json).doesNotContain("nav-agents").doesNotContain("nav-install");
        assertThat(json.indexOf("nav-chat")).isLessThan(json.indexOf("nav-my-usage"));
    }

    private static List<String> selectedIds(String json) throws Exception {
        var root = new ObjectMapper().readTree(json);
        var out = new java.util.ArrayList<String>();
        root.findParents("selected").forEach(node -> {
            if (node.path("selected").asBoolean() && node.has("id")) out.add(node.get("id").asText());
        });
        return out;
    }
}
