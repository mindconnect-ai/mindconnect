package ai.mindconnect.adminui;

import ai.mindconnect.adminui.ui.AdminLayoutAdvice;
import ai.mindconnect.adminui.ui.controller.AgentUiController;
import ai.mindconnect.agent.registry.admin.ui.RegistryUiController;
import ai.mindconnect.mcp.gateway.admin.ui.McpGatewayUiController;
import ai.mindconnect.workflow.admin.ui.WorkflowAdminUiController;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.method.HandlerTypePredicate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The admin shell — header, nav, user widget — is laid around a screen's page
 * by {@link AdminLayoutAdvice}, and the advice picks its screens by package
 * name, as strings. It has to: admin-ui-rest must not depend on the modules
 * it wraps. So the compiler cannot notice when one of those packages moves,
 * the build stays green, and the moved screen renders without header or nav.
 *
 * <p>This is the place both halves meet, so this is where it is checked — with
 * Spring's own matcher, not a copy of its rule.
 */
class AdminLayoutReachesEveryScreenTest {

    private static final HandlerTypePredicate WRAPPED = HandlerTypePredicate.forBasePackage(
            AdminLayoutAdvice.class.getAnnotation(ControllerAdvice.class).basePackages());

    @Test
    void the_mcp_gateway_screen_gets_the_admin_shell() {
        // The one that moved: mc-mcp-gateway-admin-rest became
        // mc-mcp-gateway-admin-ui-rest, and its package gained ".ui".
        assertThat(WRAPPED.test(McpGatewayUiController.class))
                .as("AdminLayoutAdvice.basePackages must cover %s",
                        McpGatewayUiController.class.getPackageName())
                .isTrue();
    }

    @Test
    void the_other_embedded_screens_keep_it_too() {
        assertThat(WRAPPED.test(AgentUiController.class)).as("admin UI").isTrue();
        assertThat(WRAPPED.test(WorkflowAdminUiController.class)).as("workflow admin").isTrue();
        assertThat(WRAPPED.test(RegistryUiController.class)).as("registry").isTrue();
    }
}
