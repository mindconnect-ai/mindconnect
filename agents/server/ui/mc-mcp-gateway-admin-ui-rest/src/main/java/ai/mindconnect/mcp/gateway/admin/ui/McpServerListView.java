package ai.mindconnect.mcp.gateway.admin.ui;

import ai.mindconnect.mcp.gateway.McpGateway;
import ai.mindconnect.mcp.gateway.McpServerRegistration;
import ai.mindconnect.mcp.gateway.McpTarget;
import ai.mindconnect.ui.model.UiAction;
import ai.mindconnect.ui.model.UiList;

import java.util.List;

/**
 * The registered MCP servers, one row each: what it is, how it is reached,
 * and how many tools it currently offers — the number is the honest health
 * indicator, because it is what the agents actually see.
 */
final class McpServerListView {

    static final String BASE = "/mcp-gateway";

    private final List<McpServerRegistration> registrations;
    private final McpGateway gateway;
    private final boolean hasCatalog;

    McpServerListView(List<McpServerRegistration> registrations,
                      McpGateway gateway, boolean hasCatalog) {
        this.registrations = registrations;
        this.gateway = gateway;
        this.hasCatalog = hasCatalog;
    }

    UiList render() {
        UiList list = UiList.of("mcp-server-list", "MCP Servers")
                .icon("plug")
                .action(UiAction.primary("create", "Register MCP Server").icon("add")
                        .dispatch("GET", BASE + "/api/new"));
        if (hasCatalog) {
            list.action(UiAction.secondary("browse", "Browse catalog").icon("box")
                    .dispatch("GET", BASE + "/api/catalog"));
        }

        for (McpServerRegistration registration : registrations) {
            list.item(UiList.Item.of(registration.id().value(), registration.displayName())
                    .description(describe(registration))
                    .href(BASE + "/" + registration.id().value())
                    .action(UiAction.danger("delete", "Delete").icon("delete")
                            .confirm("Delete MCP server '" + registration.displayName() + "'?")
                            .dispatch("DELETE", BASE + "/api/" + registration.id().value())));
        }
        return list;
    }

    /** "docker mcp/gmail:latest · 14 tool(s) · prefix gmail_" — or why not. */
    private String describe(McpServerRegistration registration) {
        StringBuilder text = new StringBuilder(target(registration.target()));
        if (!registration.enabled()) {
            return text.append(" · disabled").toString();
        }
        int tools = gateway.tools(registration.id()).size();
        text.append(" · ").append(tools == 0 ? "no tools (unreachable?)" : tools + " tool(s)");
        text.append(" · prefix ").append(registration.toolNamePrefix()).append('_');
        return text.toString();
    }

    private static String target(McpTarget target) {
        return switch (target) {
            case McpTarget.Docker docker -> "docker " + docker.image();
            case McpTarget.Process process -> "process " + String.join(" ", process.command());
            case McpTarget.Http http -> "http " + http.url();
        };
    }
}
