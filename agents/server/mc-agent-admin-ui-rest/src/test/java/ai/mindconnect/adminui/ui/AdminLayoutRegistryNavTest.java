package ai.mindconnect.adminui.ui;

import ai.mindconnect.ui.model.UiAppShell;
import ai.mindconnect.ui.model.UiMenuItem;
import ai.mindconnect.ui.model.UiPage;
import ai.mindconnect.ui.model.UiStack;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The registry screen is contributed by a module and only wired when the host
 * has a registry service. Its nav entry follows the same condition — the
 * sidebar must not offer a route nobody serves.
 */
class AdminLayoutRegistryNavTest {

    private static String menuJson(boolean registry) throws Exception {
        AdminLayout layout = new AdminLayout("mc_user", false, "0.8.2", null, null, false, registry);
        UiPage page = UiPage.of("/admin/agents", UiStack.of("body"));
        return new ObjectMapper().writeValueAsString(layout.withLayout(page));
    }

    @Test
    void with_a_registry_service_the_entry_is_there() throws Exception {
        String json = menuJson(true);

        assertThat(json).contains("nav-registry");
        assertThat(json).contains("/registry");
    }

    @Test
    void without_one_the_entry_stays_away_and_the_rest_of_the_menu_is_untouched() throws Exception {
        String json = menuJson(false);

        assertThat(json).doesNotContain("nav-registry");
        assertThat(json).contains("nav-tools");
        assertThat(json).contains("nav-workflows");
    }

    @Test
    void registry_and_migrations_sit_under_install_which_opens_on_either_page() {
        AdminLayout layout = new AdminLayout("mc_user", false, "0.8.2", null, null, false, true);

        UiMenuItem onAgents = install(layout, "/admin/agents");

        assertThat(onAgents.getChildren()).extracting(UiMenuItem::getId)
                .containsExactly("nav-registry", "nav-migrations");
        assertThat(onAgents.isOpen()).isFalse();
        assertThat(install(layout, "/admin/migrations").isOpen()).isTrue();
        assertThat(install(layout, "/registry/mindconnect-ai-mc-registry").isOpen()).isTrue();
    }

    @Test
    void without_a_registry_install_holds_migrations_alone() {
        AdminLayout layout = new AdminLayout("mc_user", false, "0.8.2", null, null, false, false);

        assertThat(install(layout, "/admin/agents").getChildren()).extracting(UiMenuItem::getId)
                .containsExactly("nav-migrations");
    }

    /** The Install group of the menu the layout draws around a page at {@code path}. */
    private static UiMenuItem install(AdminLayout layout, String path) {
        UiAppShell shell = (UiAppShell) layout.withLayout(UiPage.of(path, UiStack.of("body"))).getNode();
        return shell.getMenu().getItems().stream()
                .filter(item -> "nav-install".equals(item.getId()))
                .findFirst().orElseThrow();
    }

    @Test
    void the_older_constructor_leaves_the_entry_away() throws Exception {
        AdminLayout layout = new AdminLayout("mc_user", false, "0.8.2", null, null, true);
        UiPage page = UiPage.of("/admin/agents", UiStack.of("body"));

        assertThat(new ObjectMapper().writeValueAsString(layout.withLayout(page)))
                .doesNotContain("nav-registry");
    }
}
