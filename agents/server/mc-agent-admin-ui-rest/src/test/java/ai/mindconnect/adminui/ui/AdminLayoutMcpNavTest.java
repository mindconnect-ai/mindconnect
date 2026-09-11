package ai.mindconnect.adminui.ui;

import ai.mindconnect.ui.model.UiPage;
import ai.mindconnect.ui.model.UiStack;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The MCP screen is contributed by a module and only wired when the host has
 * a gateway bean. Its nav entry has to follow the same condition, or the
 * sidebar offers a route nobody serves.
 */
class AdminLayoutMcpNavTest {

    private static String menuJson(boolean mcpGateway) throws Exception {
        AdminLayout layout = new AdminLayout("mc_user", false, "0.6.1", null, null, mcpGateway);
        UiPage page = UiPage.of("/admin/agents", UiStack.of("body"));
        return new ObjectMapper().writeValueAsString(layout.withLayout(page));
    }

    @Test
    void with_a_gateway_the_entry_is_there() throws Exception {
        String json = menuJson(true);

        assertThat(json).contains("nav-mcp");
        assertThat(json).contains("/mcp-gateway");
    }

    @Test
    void without_a_gateway_the_entry_stays_away() throws Exception {
        // The point of this test: it did not. mindconnect.mcp.enabled=false
        // takes the gateway, the registry admin and the controller with it —
        // but the sidebar still showed "MCP Servers", and the click 404'd.
        String json = menuJson(false);

        assertThat(json).doesNotContain("nav-mcp");
        assertThat(json).doesNotContain("/mcp-gateway");
        assertThat(json).contains("nav-tools");        // the rest of the menu is untouched
        assertThat(json).contains("nav-workflows");
    }
}
