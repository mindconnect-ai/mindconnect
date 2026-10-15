package ai.mindconnect.adminui.ui;

import ai.mindconnect.ui.model.UiPage;
import ai.mindconnect.ui.model.UiStack;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Somebody who is a user of the namespace they work in — not one of its
 * admins — sees the chat and nothing else. An entry they may not open is
 * worse than no entry: the server refuses those routes anyway
 * ({@code NamespaceAccessInterceptor}), and a sidebar full of 403s explains
 * nothing.
 */
class AdminLayoutChatOnlyNavTest {

    private static String menuJson(boolean chatOnly) throws Exception {
        AdminLayout layout = new AdminLayout("alice", true, "0.8.3", null, null, true, true)
                .chatOnly(chatOnly);
        UiPage page = UiPage.of("/chat", UiStack.of("body"));
        return new ObjectMapper().writeValueAsString(layout.withLayout(page));
    }

    @Test
    void a_user_of_the_namespace_gets_the_chat_and_the_version() throws Exception {
        String json = menuJson(true);

        assertThat(json).contains("nav-chat").contains("\"/chat\"");
        assertThat(json).as("which build is running is nobody's secret").contains("nav-version");
        assertThat(json).doesNotContain("nav-agents").doesNotContain("nav-tools")
                .doesNotContain("nav-skills").doesNotContain("nav-llm-configs")
                .doesNotContain("nav-workflows").doesNotContain("nav-mcp")
                .doesNotContain("nav-vector-stores").doesNotContain("nav-install")
                .doesNotContain("nav-api");
    }

    @Test
    void an_admin_gets_the_whole_navigation() throws Exception {
        String json = menuJson(false);

        assertThat(json).contains("nav-chat").contains("nav-agents").contains("nav-workflows")
                .contains("nav-llm-configs").contains("nav-vector-stores").contains("nav-api")
                .contains("nav-version");
    }

    @Test
    void the_header_and_the_namespace_switcher_stay_either_way() throws Exception {
        AdminLayout layout = new AdminLayout("alice", true, "0.8.3").chatOnly(true)
                .namespaces(new AdminLayout.NamespaceSwitch("acme", "ACME",
                        java.util.List.of(new AdminLayout.NamespaceSwitch.Entry("acme", "ACME"),
                                new AdminLayout.NamespaceSwitch.Entry("alice", "alice"))));

        String json = new ObjectMapper().writeValueAsString(
                layout.withLayout(UiPage.of("/chat", UiStack.of("body"))));

        assertThat(json).as("the same person may be an admin of another namespace")
                .contains("namespace-switch-alice");
    }
}
