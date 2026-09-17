package ai.mindconnect.adminui.ui;

import ai.mindconnect.ui.model.UiAppShell;
import ai.mindconnect.ui.model.UiPage;
import ai.mindconnect.ui.model.UiStack;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The header says what this installation is called. Unset, that is still
 * Mindconnect; configured, it is the installation's own name, mark and home.
 */
class AdminLayoutBrandingTest {

    private static UiAppShell shell(AdminLayout layout) {
        UiPage page = UiPage.of("/admin/agents", UiStack.of("body"));
        return (UiAppShell) layout.withLayout(page).getNode();
    }

    @Test
    void without_branding_the_header_is_the_one_that_shipped() {
        var header = shell(new AdminLayout("mc_user", false, "0.8.3")).getHeader();

        assertThat(header.getBrand()).isEqualTo("Mindconnect Agent Runtime");
        assertThat(header.getBrandLogo()).isEqualTo("/img/logo.svg");
        assertThat(header.getBrandHref()).isEqualTo("/admin/agents");
    }

    @Test
    void a_brand_reaches_heading_logo_and_link() {
        AdminLayout layout = new AdminLayout("mc_user", false, "0.8.3")
                .brand(new AdminLayout.Brand("ACME Assistants", "/branding/acme.svg", "/chat"));

        var header = shell(layout).getHeader();

        assertThat(header.getBrand()).isEqualTo("ACME Assistants");
        assertThat(header.getBrandLogo()).isEqualTo("/branding/acme.svg");
        assertThat(header.getBrandHref()).isEqualTo("/chat");
    }

    @Test
    void a_brand_without_a_logo_leaves_the_header_with_the_name_alone() {
        AdminLayout layout = new AdminLayout("mc_user", false, "0.8.3")
                .brand(new AdminLayout.Brand("ACME Assistants", null, "/admin/agents"));

        var header = shell(layout).getHeader();

        assertThat(header.getBrand()).isEqualTo("ACME Assistants");
        assertThat(header.getBrandLogo()).isNull();
    }

    @Test
    void the_rest_of_the_shell_is_untouched_by_branding() {
        AdminLayout layout = new AdminLayout("mc_user", false, "0.8.3")
                .brand(new AdminLayout.Brand("ACME Assistants", null, "/admin/agents"));

        UiAppShell shell = shell(layout);

        assertThat(shell.getMenu()).isNotNull();
        assertThat(shell.getHeader().getUser().getName()).isEqualTo("mc_user");
    }
}
