package ai.mindconnect.mcp.gateway.admin.ui;

import ai.mindconnect.mcp.gateway.McpCatalog;
import ai.mindconnect.mcp.gateway.McpCatalogEntry;
import ai.mindconnect.mcp.gateway.McpGateway;
import ai.mindconnect.mcp.gateway.McpProbeResult;
import ai.mindconnect.mcp.gateway.McpRegistryAdmin;
import ai.mindconnect.mcp.gateway.McpServerRegistration;
import ai.mindconnect.mcp.gateway.McpTarget;
import ai.mindconnect.mcp.gateway.McpServerId;
import ai.mindconnect.ui.model.UiPage;
import ai.mindconnect.ui.model.UiPatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The MCP gateway's screen: list the registered servers, register one, try
 * it out, delete it.
 *
 * <p>Pages live under {@code /mcp-gateway}, the actions behind them under
 * {@code /mcp-gateway/api} — the same split the workflow admin uses, so a
 * host's layout advice can wrap the pages and leave the rest alone.
 */
@RestController
@RequestMapping(McpServerListView.BASE)
public class McpGatewayUiController {

    private static final Logger log = LoggerFactory.getLogger(McpGatewayUiController.class);

    private final McpRegistryAdmin admin;
    private final McpGateway gateway;
    /** Null when this installation offers no catalog to browse. */
    private final McpCatalog catalog;

    public McpGatewayUiController(McpRegistryAdmin admin, McpGateway gateway, McpCatalog catalog) {
        this.admin = admin;
        this.gateway = gateway;
        this.catalog = catalog;
    }

    @GetMapping
    public UiPage page() {
        return list();
    }

    @GetMapping("/api")
    public UiPage list() {
        return UiPage.of(McpServerListView.BASE,
                new McpServerListView(admin.all(), gateway, catalog != null).render());
    }

    /** The catalog screen; the search field posts back here. */
    @GetMapping({"/api/catalog", "/catalog"})
    public UiPage catalog() {
        return catalogPage(null);
    }

    @PostMapping("/api/catalog")
    public UiPage searchCatalog(@RequestBody Map<String, Object> body) {
        Object q = body.get("q");
        return catalogPage(q == null ? null : q.toString());
    }

    /**
     * Takes a catalog entry over into the registration form. Nothing is
     * saved — the entry's required environment variables arrive empty, and
     * an operator fills them in, tests, and decides.
     */
    @GetMapping("/api/catalog/{entryId}")
    public UiPage takeOver(@PathVariable String entryId) {
        if (catalog == null) {
            return list();
        }
        Optional<McpCatalogEntry> found = catalog.search(entryId, 50).stream()
                .filter(e -> e.id().equals(entryId))
                .findFirst();
        if (found.isEmpty()) {
            return catalogPage(entryId);
        }
        McpCatalogEntry entry = found.get();
        McpServerDraft draft = new McpServerDraft(entry.id(), entry.title(), entry.description(),
                true, ToolNamePrefixes.suggest(entry.id(), entry.toolNames()),
                McpTargetForm.toJson(entry.target()));
        return UiPage.of(McpServerListView.BASE + "/new",
                new McpServerFormView(draft, true, null, null, entry).render());
    }

    private UiPage catalogPage(String query) {
        List<McpCatalogEntry> results = catalog == null ? List.of() : catalog.search(query, 40);
        String name = catalog == null ? "none" : catalog.name();
        return UiPage.of(McpServerListView.BASE + "/catalog",
                new McpCatalogView(name, query, results).render());
    }

    @GetMapping({"/api/new", "/new"})
    public UiPage newServer() {
        return UiPage.of(McpServerListView.BASE + "/new",
                new McpServerFormView(McpServerDraft.empty(), true, null, null, null).render());
    }

    @GetMapping("/{id}")
    public UiPage edit(@PathVariable String id) {
        return serverIdOf(id).flatMap(admin::findById)
                .map(registration -> UiPage.of(McpServerListView.BASE + "/" + id,
                        new McpServerFormView(McpServerDraft.of(registration), false, null, null, null,
                                admin.discovery(registration.id())).render()))
                .orElseGet(this::list);
    }

    /**
     * The id from a URL or a form, as a {@link McpServerId} — or empty when
     * the text cannot name a server at all. Empty rather than thrown: an address nobody could have registered is simply
     * not found, and a form being re-rendered after a failed save must not
     * fail again on the same text.
     */
    private Optional<McpServerId> serverIdOf(String value) {
        try {
            return Optional.of(McpServerId.of(value));
        } catch (IllegalArgumentException notAnId) {
            return Optional.empty();
        }
    }

    /**
     * Starts the described server, lists its tools, shuts it down — and puts
     * the answer under the form. Nothing is saved.
     *
     * <p>A patch rather than a page: a page carries a navigation, and
     * navigating away from a form the operator is still filling in would
     * throw the answer away together with everything typed.
     */
    @PostMapping("/api/probe")
    public UiPatch probe(@RequestBody Map<String, Object> body) {
        return probe(null, body);
    }

    /**
     * The variant for an existing server: its id comes from the URL, because
     * the form shows it read-only and a read-only field is not submitted.
     */
    @PostMapping("/api/{id}/probe")
    public UiPatch probe(@PathVariable(required = false) String id,
                         @RequestBody Map<String, Object> body) {
        Form form = new Form(body, id);
        try {
            McpProbeResult result = admin.probe(form.toRegistration());
            return form.patch(result, null);
        } catch (IllegalArgumentException e) {
            return form.patch(null, e.getMessage());
        }
    }

    @PostMapping("/api/save")
    public UiPage save(@RequestBody Map<String, Object> body) {
        return save(null, body);
    }

    @PostMapping("/api/{id}/save")
    public UiPage save(@PathVariable(required = false) String id,
                       @RequestBody Map<String, Object> body) {
        Form form = new Form(body, id);
        try {
            McpServerRegistration registration = form.toRegistration();
            admin.save(registration);
            log.info("MCP server '{}' registered via the admin UI", registration.id());
            return list();
        } catch (IllegalArgumentException e) {
            // The same form again, with the message and everything still in it.
            McpServerDraft draft = form.draft();
            boolean isNew = form.isNew();
            return UiPage.of(McpServerListView.BASE + (isNew ? "/new" : "/" + draft.id()),
                    new McpServerFormView(draft, isNew, null, e.getMessage(), null).render());
        }
    }

    /**
     * Forgets what was discovered and reads it again right away, so the
     * answer is on screen instead of appearing at some later lookup.
     */
    @PostMapping("/api/{id}/refresh")
    public UiPage refresh(@PathVariable String id) {
        McpServerId server = McpServerId.of(id);
        admin.refresh(server);
        gateway.tools(server);             // ask now, not at some later lookup
        return edit(id);
    }

    @DeleteMapping("/api/{id}")
    public UiPage delete(@PathVariable String id) {
        admin.delete(McpServerId.of(id));
        return list();
    }

    /** The submitted fields, and how they become a registration. */
    private final class Form {

        private final Map<String, Object> raw;
        /** From the URL when editing; the form does not submit a read-only id. */
        private final String idFromPath;
        /** What is stored under {@link #id()}, looked up at most once. */
        private Optional<McpServerRegistration> stored;

        Form(Map<String, Object> raw, String idFromPath) {
            this.raw = raw;
            this.idFromPath = idFromPath;
        }

        private Optional<McpServerRegistration> stored() {
            if (stored == null) {
                String id = id();
                stored = serverIdOf(id).flatMap(admin::findById);
            }
            return stored;
        }

        boolean isNew() {
            return stored().isEmpty();
        }

        /**
         * The prefix, and where it comes from. On an existing registration it
         * is fixed (concept 23 §5): it is the head of every tool name this
         * server contributes, agents have bound those names, and renaming it
         * silently orphans their bindings and the operator's tool settings
         * alike. The form therefore shows it read-only — and a read-only
         * field is not submitted, so the stored value is the answer.
         *
         * <p>Non-throwing on purpose: this is also called while re-rendering
         * a form that already failed. {@link #toRegistration()} is where a
         * submitted change is refused.
         */
        private String prefix() {
            return stored().map(McpServerRegistration::toolNamePrefix)
                    .orElseGet(() -> str("toolNamePrefix"));
        }

        /** Exactly what was submitted, valid or not — for rendering the form again. */
        McpServerDraft draft() {
            return new McpServerDraft(id(), str("displayName"), str("description"),
                    bool("enabled"), prefix(), str("target"));
        }

        private String id() {
            return idFromPath != null && !idFromPath.isBlank() ? idFromPath : str("id");
        }

        McpServerRegistration toRegistration() {
            String id = id();
            if (id.isBlank()) {
                throw new IllegalArgumentException("Id is required.");
            }
            // A caller that reaches past the form — the API, a script — could
            // still send a different prefix. Refused rather than ignored: a
            // field that is silently dropped is worse than one that is
            // missing, and this one would take agent bindings with it.
            String submittedPrefix = str("toolNamePrefix");
            String storedPrefix = stored().map(McpServerRegistration::toolNamePrefix).orElse(null);
            if (storedPrefix != null && !submittedPrefix.isBlank()
                    && !submittedPrefix.equals(storedPrefix)) {
                throw new IllegalArgumentException(
                        "The tool name prefix is fixed after creation — agents bind the tool names "
                                + "it produces. It stays '" + storedPrefix + "'. Register a new server "
                                + "to use '" + submittedPrefix + "'.");
            }
            String prefix = prefix();
            if (prefix.isBlank()) {
                throw new IllegalArgumentException("Tool name prefix is required.");
            }
            McpTarget target = McpTargetForm.fromJson(str("target"));
            return new McpServerRegistration(McpServerId.of(id), str("displayName"),
                    nullIfBlank(str("description")), bool("enabled"), prefix, target, null);
        }

        /** The form again, keeping what was typed, plus a result or an error. */
        UiPatch patch(McpProbeResult probe, String error) {
            McpServerDraft draft = draft();
            return UiPatch.of().patch(UiPatch.Operation.replace(McpServerFormView.STACK_ID,
                    new McpServerFormView(draft, isNew(), probe, error, null).render()));
        }

        String str(String key) {
            Object value = raw.get(key);
            return value == null ? "" : value.toString().trim();
        }

        private boolean bool(String key) {
            Object value = raw.get(key);
            if (value instanceof Boolean b) return b;
            String text = str(key);
            return text.isEmpty() || "true".equalsIgnoreCase(text) || "on".equalsIgnoreCase(text);
        }

        private static String nullIfBlank(String value) {
            return value == null || value.isBlank() ? null : value;
        }
    }
}
