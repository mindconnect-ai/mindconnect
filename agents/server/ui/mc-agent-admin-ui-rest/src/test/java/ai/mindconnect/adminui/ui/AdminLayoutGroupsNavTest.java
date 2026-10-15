package ai.mindconnect.adminui.ui;

import ai.mindconnect.ui.model.UiAppShell;
import ai.mindconnect.ui.model.UiMenuItem;
import ai.mindconnect.ui.model.UiPage;
import ai.mindconnect.ui.model.UiStack;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The admin entries sit in three groups: AI (what thinks), Tools (what an
 * agent can call) and Data (what it reads). A group opens while one of its
 * pages is the current one, so the selected entry is never folded away.
 */
class AdminLayoutGroupsNavTest {

    @Test
    void the_admin_entries_sit_in_ai_tools_and_data_in_that_order() {
        AdminLayout layout = new AdminLayout("mc_user", false, "0.8.3", null, null, true, true);

        List<UiMenuItem> items = menu(layout, "/admin/agents");

        assertThat(items).extracting(UiMenuItem::getId)
                .containsExactly("nav-chat", "nav-group-ai", "nav-group-tools", "nav-group-data",
                        "nav-install", "nav-api", "nav-version");
        assertThat(group(items, "nav-group-ai").getChildren()).extracting(UiMenuItem::getId)
                .containsExactly("nav-agents", "nav-llm-configs", "nav-skills");
        assertThat(group(items, "nav-group-tools").getChildren()).extracting(UiMenuItem::getId)
                .containsExactly("nav-tools", "nav-mcp", "nav-workflows");
        assertThat(group(items, "nav-group-data").getChildren()).extracting(UiMenuItem::getId)
                .containsExactly("nav-vector-stores", "nav-memories");
    }

    @Test
    void without_a_gateway_tools_holds_tools_and_workflows_alone() {
        AdminLayout layout = new AdminLayout("mc_user", false, "0.8.3", null, null, false, false);

        assertThat(group(menu(layout, "/admin/agents"), "nav-group-tools").getChildren())
                .extracting(UiMenuItem::getId).containsExactly("nav-tools", "nav-workflows");
    }

    @Test
    void a_group_opens_on_its_own_pages_and_stays_closed_elsewhere() {
        AdminLayout layout = new AdminLayout("mc_user", false, "0.8.3", null, null, true, false);

        assertThat(group(menu(layout, "/admin/agents"), "nav-group-ai").isOpen()).isTrue();
        assertThat(group(menu(layout, "/admin/sessions/42"), "nav-group-ai").isOpen()).isTrue();
        assertThat(group(menu(layout, "/admin/agents"), "nav-group-tools").isOpen()).isFalse();
        assertThat(group(menu(layout, "/mcp-gateway"), "nav-group-tools").isOpen()).isTrue();
        assertThat(group(menu(layout, "/workflow-admin/x"), "nav-group-tools").isOpen()).isTrue();
        assertThat(group(menu(layout, "/admin/vector-stores"), "nav-group-data").isOpen()).isTrue();
        assertThat(group(menu(layout, "/admin/vector-stores"), "nav-group-ai").isOpen()).isFalse();
    }

    private static List<UiMenuItem> menu(AdminLayout layout, String path) {
        UiAppShell shell = (UiAppShell) layout.withLayout(UiPage.of(path, UiStack.of("body"))).getNode();
        return shell.getMenu().getItems();
    }

    private static UiMenuItem group(List<UiMenuItem> items, String id) {
        return items.stream().filter(item -> id.equals(item.getId())).findFirst().orElseThrow();
    }
}
