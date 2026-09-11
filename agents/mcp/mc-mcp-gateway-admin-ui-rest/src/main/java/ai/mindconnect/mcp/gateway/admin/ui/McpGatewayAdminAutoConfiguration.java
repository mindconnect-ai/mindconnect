package ai.mindconnect.mcp.gateway.admin.ui;

import ai.mindconnect.mcp.gateway.McpCatalog;
import ai.mindconnect.mcp.gateway.McpGateway;
import ai.mindconnect.mcp.gateway.McpRegistryAdmin;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * Adds the MCP gateway screen to a host application that has a gateway to
 * administer. Without both ports there is nothing to manage, and the screen
 * stays away rather than rendering an empty shell.
 *
 * <p>The ordering hint names the in-process gateway's configuration by
 * string rather than by class: {@code @ConditionalOnBean} only sees what
 * has been registered by the time it runs, so an auto-configuration that
 * asks about another one has to run after it — and this module must not
 * depend on any particular implementation to say so.
 */
@AutoConfiguration
@AutoConfigureAfter(name = "ai.mindconnect.mcp.gateway.local.McpGatewayAutoConfiguration")
public class McpGatewayAdminAutoConfiguration {

    @Bean
    @ConditionalOnBean({McpRegistryAdmin.class, McpGateway.class})
    @ConditionalOnMissingBean
    public McpGatewayUiController mcpGatewayUiController(McpRegistryAdmin admin, McpGateway gateway,
                                                         ObjectProvider<McpCatalog> catalog) {
        // The catalog is optional; without one the screen simply has no
        // "browse" button.
        return new McpGatewayUiController(admin, gateway, catalog.getIfAvailable());
    }
}
