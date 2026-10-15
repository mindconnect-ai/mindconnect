package ai.mindconnect.mcp.gateway.admin.ui;

import ai.mindconnect.mcp.gateway.McpCatalog;
import ai.mindconnect.mcp.gateway.McpCatalogEntry;
import ai.mindconnect.ui.model.UiAction;
import ai.mindconnect.ui.model.UiField;
import ai.mindconnect.ui.model.UiForm;
import ai.mindconnect.ui.model.UiList;
import ai.mindconnect.ui.model.UiNode;
import ai.mindconnect.ui.model.UiStack;
import ai.mindconnect.ui.model.UiTrigger;

import java.util.List;

/**
 * Browsing somebody else's directory of MCP servers: search, see what a
 * server offers, take it over into the registration form.
 *
 * <p>"Take over" fills the form — it does not register anything. Between
 * the catalog and a running container there is always an operator who read
 * what the entry says and pressed Save.
 */
final class McpCatalogView {

    static final String STACK_ID = "mcp-catalog-stack";
    private static final String FORM_ID = "mcp-catalog-search";

    private final String catalogName;
    private final String query;
    private final List<McpCatalogEntry> results;

    McpCatalogView(String catalogName, String query, List<McpCatalogEntry> results) {
        this.catalogName = catalogName;
        this.query = query;
        this.results = results;
    }

    UiNode render() {
        UiForm search = UiForm.of(FORM_ID, null);
        search.field(UiField.text("q", "", query)
                .asEditable()
                .icon("search")
                .placeholder("Search " + catalogName + "…")
                .onChange(UiTrigger.api("POST", McpServerListView.BASE + "/api/catalog", FORM_ID)));

        UiList list = UiList.of("mcp-catalog-list", "Catalog — " + catalogName).icon("box");
        list.headerExtra(search);
        list.action(UiAction.secondary("back", "Registered servers").icon("back")
                .dispatch("GET", McpServerListView.BASE + "/api"));

        if (results.isEmpty()) {
            list.item(UiList.Item.of("none", query == null || query.isBlank()
                            ? "Catalog unavailable"
                            : "Nothing matches '" + query + "'")
                    .description(query == null || query.isBlank()
                            ? "The catalog could not be read. It is fetched from the internet; "
                              + "an installation without outbound access registers servers by hand."
                            : "Try a shorter search term."));
            return UiStack.of(STACK_ID).child(list);
        }

        for (McpCatalogEntry entry : results) {
            list.item(UiList.Item.of("cat-" + entry.id(), entry.title())
                    .description(describe(entry))
                    .action(UiAction.primary("take-" + entry.id(), "Take over").icon("add")
                            .dispatch("GET", McpServerListView.BASE + "/api/catalog/" + entry.id())));
        }
        return UiStack.of(STACK_ID).child(list);
    }

    /** Tool count first — it is what an operator is actually shopping for. */
    private static String describe(McpCatalogEntry entry) {
        StringBuilder text = new StringBuilder();
        if (!entry.toolNames().isEmpty()) {
            text.append(entry.toolNames().size()).append(" tool(s) · ");
        }
        if (!entry.requiredEnv().isEmpty()) {
            text.append("needs ")
                .append(String.join(", ", entry.requiredEnv().stream()
                        .map(McpCatalogEntry.RequiredValue::envName).toList()))
                .append(" · ");
        }
        String description = entry.description() == null ? "" : entry.description().strip();
        if (description.length() > 160) {
            description = description.substring(0, 160) + "…";
        }
        return text.append(description).toString();
    }
}
