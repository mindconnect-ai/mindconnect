package ai.mindconnect.mcp.gateway.admin.ui;

import ai.mindconnect.mcp.gateway.McpCatalogEntry;
import ai.mindconnect.mcp.gateway.McpDiscovery;
import ai.mindconnect.mcp.gateway.McpProbeResult;
import ai.mindconnect.mcp.gateway.McpTool;
import ai.mindconnect.ui.model.UiAction;
import ai.mindconnect.ui.model.UiField;
import ai.mindconnect.ui.model.UiForm;
import ai.mindconnect.ui.model.UiNode;
import ai.mindconnect.ui.model.UiStack;
import ai.mindconnect.ui.model.UiText;

/**
 * The form for registering an MCP server, and — once someone pressed "Test
 * connection" — what the server answered.
 *
 * <p>Testing before saving is the point of this screen. A registration that
 * cannot start is otherwise silent: the tools simply do not appear, and the
 * reason sits in a log.
 */
final class McpServerFormView {

    private static final String FORM_ID = "mcp-server-form";

    /** The node a probe replaces in place, so the page does not navigate. */
    static final String STACK_ID = FORM_ID + "-stack";

    private final McpServerDraft draft;
    private final boolean isNew;
    private final McpProbeResult probe;
    private final String error;
    /** Set when the form was prefilled from a catalog entry. */
    private final McpCatalogEntry origin;
    /** What is known about this server's tools; null for an unsaved one. */
    private final McpDiscovery discovery;

    McpServerFormView(McpServerDraft draft, boolean isNew,
                      McpProbeResult probe, String error, McpCatalogEntry origin) {
        this(draft, isNew, probe, error, origin, null);
    }

    McpServerFormView(McpServerDraft draft, boolean isNew,
                      McpProbeResult probe, String error, McpCatalogEntry origin,
                      McpDiscovery discovery) {
        this.draft = draft == null ? McpServerDraft.empty() : draft;
        this.isNew = isNew;
        this.probe = probe;
        this.error = error;
        this.origin = origin;
        this.discovery = discovery;
    }

    String title() {
        return isNew ? "Register MCP Server" : "MCP Server " + draft.id();
    }

    /**
     * Where this form posts to. For an existing server the id travels in the
     * URL: it is shown read-only, and a read-only field is not submitted —
     * relying on it would lose the id on every save.
     */
    private String actionBase() {
        return isNew
                ? McpServerListView.BASE + "/api"
                : McpServerListView.BASE + "/api/" + draft.id();
    }

    UiNode render() {
        UiForm form = UiForm.of(FORM_ID, title());

        UiField id = UiField.text("id", "Id", draft.id())
                .hint("Lower-case letters, digits, '.', '_' or '-', starting with a letter or digit. "
                        + "Names the registration file and cannot change later.");
        form.field(isNew ? id.asEditable().asRequired() : id);

        form.field(UiField.text("displayName", "Display name", draft.displayName())
                .asEditable()
                .hint("Shown in this list. Defaults to the id."));

        form.field(UiField.text("description", "Description", draft.description())
                .asEditable()
                .hint("What this server is for."));

        // Fixed after creation, like the id above and for a sharper reason:
        // it is the head of every tool name this server contributes, and
        // agents have bound those names (concept 23 §5).
        UiField prefix = UiField.text("toolNamePrefix", "Tool name prefix", draft.toolNamePrefix())
                .hint(isNew
                        ? "Prefix of every tool this server contributes: 'gmail' makes its "
                                + "search_emails appear as gmail_search_emails. Letters, digits, "
                                + "'_' or '-'. Choose it now — it cannot change later."
                        : "Fixed after creation: agents bind the tool names it produces, and "
                                + "renaming it would take their bindings and this server's tool "
                                + "settings with it. Register a new server to use a different one.");
        form.field(isNew ? prefix.asEditable().asRequired() : prefix);

        form.field(UiField.bool("enabled", "Enabled", draft.enabled())
                .asEditable()
                .hint("A disabled server keeps its registration but contributes no tools."));

        form.field(UiField.textarea("target", "Target (JSON)", draft.targetJson())
                .asEditable().asRequired()
                .hint("""
                        How to reach the server — one of three shapes:
                        {"type":"http","url":"https://mcp.example.com/mcp","headers":{"Authorization":"Bearer …"}}
                        {"type":"docker","image":"…","mounts":[{"hostPath":"~/x","containerPath":"/x"}],"env":{}}
                        {"type":"process","command":["npx","-y","…"],"env":{}}
                        A remote server needs https unless it runs on this machine.
                        A value in "env" or "headers" is stored and sent as entered. ${NAME} is
                        reserved for variables of the signed-in user, which do not exist yet —
                        a registration that uses one cannot start."""));

        form.action(UiAction.secondary("probe", "Test connection").icon("flash")
                        .dispatch("POST", actionBase() + "/probe", FORM_ID))
                .action(UiAction.primary("save", "Save").icon("save")
                        .dispatch("POST", actionBase() + "/save", FORM_ID))
                .action(UiAction.secondary("back", "All MCP servers").icon("back")
                        .dispatch("GET", McpServerListView.BASE + "/api"));

        UiStack stack = UiStack.of(STACK_ID);
        if (origin != null) {
            stack.child(catalogNote());
        }
        stack.child(form);
        if (error != null) {
            stack.child(message(FORM_ID + "-error", "✗ " + error, false));
        }
        if (probe != null) {
            stack.child(probeResult());
        }
        if (discovery != null) {
            stack.child(knownTools());
        }
        return stack;
    }

    /**
     * What the catalog said about this entry, above the form: whose code
     * this is, what it claims to offer, and which values have to be filled
     * in. Registering means running somebody else's container — the moment
     * to say so is before the operator presses Save, not in a log
     * afterwards.
     */
    private UiNode catalogNote() {
        StringBuilder text = new StringBuilder("From the catalog: ")
                .append(origin.title());
        if (origin.sourceUrl() != null) {
            text.append("  ·  source: ").append(origin.sourceUrl());
        }
        if (!origin.toolNames().isEmpty()) {
            text.append("\nOffers ").append(origin.toolNames().size()).append(" tool(s): ")
                .append(String.join(", ", origin.toolNames().subList(0,
                        Math.min(8, origin.toolNames().size()))));
            if (origin.toolNames().size() > 8) {
                text.append(", …");
            }
        }
        for (McpCatalogEntry.RequiredValue value : origin.requiredEnv()) {
            text.append("\nNeeds ").append(value.envName());
            if (value.description() != null) {
                text.append(" — ").append(value.description());
            }
        }
        text.append("\nNothing has been contacted yet. Saving this runs the image on this machine.");
        return UiStack.of(FORM_ID + "-origin")
                .<UiStack>withCssClass("llm-test-result")
                .child(UiText.of(FORM_ID + "-origin-text", text.toString())
                        .<UiText>withCssClass("llm-test-body"));
    }

    /**
     * The tools this server is currently known to offer, and when that was
     * learned — plus the button to learn it again.
     *
     * <p>The date is the point. Discovery is remembered rather than repeated,
     * so what an agent sees can be older than the server: a new image tag
     * behind the same name changes nothing until somebody re-reads.
     */
    private UiNode knownTools() {
        StringBuilder text = new StringBuilder();
        if (discovery.fetchedAt() == null) {
            text.append("Not read yet. The tools appear once an agent uses this server, "
                    + "or right away if you read them now.");
        } else {
            text.append(discovery.tools().size()).append(" tool(s), read ")
                .append(age(discovery.fetchedAt()));
            for (McpTool tool : discovery.tools()) {
                text.append('\n').append(draftPrefix()).append('_').append(tool.name());
                if (tool.description() != null && !tool.description().isBlank()) {
                    String line = tool.description().strip();
                    int newline = line.indexOf('\n');
                    if (newline > 0) line = line.substring(0, newline);
                    if (line.length() > 110) line = line.substring(0, 110) + "…";
                    text.append(" — ").append(line);
                }
            }
        }

        UiForm form = UiForm.of(FORM_ID + "-known", "Known tools");
        form.field(UiField.textarea("knownTools", null, text.toString())
                .hint("Names as the agent sees them: the prefix above plus the server's own name."));
        form.action(UiAction.secondary("reread", "Re-read tools").icon("refresh")
                .dispatch("POST", McpServerListView.BASE + "/api/" + draft.id() + "/refresh"));
        return form;
    }

    /** "2 hours ago", "3 days ago" — a date needs no more precision here. */
    private static String age(java.time.Instant when) {
        java.time.Duration since = java.time.Duration.between(when, java.time.Instant.now());
        if (since.toMinutes() < 1) return "just now";
        if (since.toHours() < 1) return since.toMinutes() + " min ago";
        if (since.toDays() < 1) return since.toHours() + " hour(s) ago";
        return since.toDays() + " day(s) ago";
    }

    private UiNode probeResult() {
        String head = (probe.ok() ? "✓ " : "✗ ") + probe.message() + " · " + probe.durationMs() + " ms";
        UiStack stack = UiStack.of(FORM_ID + "-probe")
                .<UiStack>withCssClass(probe.ok() ? "llm-test-result llm-test-result--ok"
                        : "llm-test-result llm-test-result--err");
        stack.child(UiText.of(FORM_ID + "-probe-head", head).<UiText>withCssClass("llm-test-meta"));
        if (!probe.tools().isEmpty()) {
            StringBuilder names = new StringBuilder();
            for (McpTool tool : probe.tools()) {
                if (names.length() > 0) names.append('\n');
                names.append(draftPrefix()).append('_').append(tool.name())
                        .append(" — ").append(tool.description() == null ? "" : tool.description());
            }
            stack.child(UiText.of(FORM_ID + "-probe-tools", names.toString())
                    .<UiText>withCssClass("llm-test-body"));
        }
        return stack;
    }

    private String draftPrefix() {
        return draft.toolNamePrefix() == null || draft.toolNamePrefix().isBlank()
                ? "?" : draft.toolNamePrefix();
    }

    private static UiNode message(String id, String text, boolean ok) {
        return UiStack.of(id)
                .<UiStack>withCssClass(ok ? "llm-test-result llm-test-result--ok"
                        : "llm-test-result llm-test-result--err")
                .child(UiText.of(id + "-text", text).<UiText>withCssClass("llm-test-meta"));
    }
}
