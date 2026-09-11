package ai.mindconnect.adminui.ui.controller;


import ai.mindconnect.adminui.service.ToolTestService;
import ai.mindconnect.adminui.ui.AdminLayoutFactory;
import ai.mindconnect.adminui.ui.component.ToolCatalogComponent;
import ai.mindconnect.adminui.ui.component.ToolCatalogTestComponent;
import ai.mindconnect.adminui.ui.component.ToolSettingsComponent;
import ai.mindconnect.adminui.ui.page.ToolListPage;
import ai.mindconnect.agent.tool.AgentTool;
import ai.mindconnect.agent.tool.Tool;
import ai.mindconnect.agent.tool.OverlayToolRegistry;
import ai.mindconnect.agent.tool.ToolRepository;
import ai.mindconnect.agent.tool.ToolSettings;
import ai.mindconnect.agent.tool.ToolRegistry;
import ai.mindconnect.agent.tool.ToolCallScope;
import ai.mindconnect.chatui.ui.controller.FormBody;
import ai.mindconnect.agent.runtime.service.AgentChatService;
import ai.mindconnect.mcp.gateway.McpRegistryAdmin;
import ai.mindconnect.ui.model.UiAction;
import ai.mindconnect.ui.model.UiDialog;
import ai.mindconnect.ui.model.UiPage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Read-only catalog of all tools the runtime can provide. Mirrors the
 * per-agent tool config, but lists the whole registry — the union of built-in
 * tools and provider-contributed tools — so an operator can see what's
 * available without opening an agent. Tools are not editable here.
 */
@RestController
@RequestMapping("/admin/api/tools")
public class ToolCatalogUiController {

    private static final Logger log = LoggerFactory.getLogger(ToolCatalogUiController.class);

    private final ToolRegistry toolRegistry;
    private final ToolTestService toolTestService;
    private final AdminLayoutFactory layoutFactory;
    /** Present when this installation can administer MCP servers. */
    private final ObjectProvider<McpRegistryAdmin> mcpRegistryAdmin;
    /** Present when tool settings can be stored; absent means the catalog is read-only. */
    private final ObjectProvider<ToolRepository> toolRepository;
    /**
     * The registry beneath the operator's decisions. The catalog is the one
     * screen that needs both: the effective view for what a tool tells the
     * model today, and the source for what it would say without an override
     * — and for the rows of tools that were switched off, which the
     * effective view no longer has.
     */
    private final ToolRegistry sourceRegistry;

    public ToolCatalogUiController(ToolRegistry toolRegistry,
                                 ToolTestService toolTestService,
                                 AdminLayoutFactory layoutFactory,
                                 ObjectProvider<McpRegistryAdmin> mcpRegistryAdmin,
                                 ObjectProvider<ToolRepository> toolRepository) {
        this.toolRegistry = toolRegistry;
        this.toolTestService = toolTestService;
        this.layoutFactory = layoutFactory;
        this.mcpRegistryAdmin = mcpRegistryAdmin;
        this.toolRepository = toolRepository;
        this.sourceRegistry = toolRegistry instanceof OverlayToolRegistry overlay
                ? overlay.source()
                : toolRegistry;
    }

    /**
     * Ways to add to the catalog, offered where an operator looks at it.
     * Most tools arrive with a module on the classpath and cannot be added
     * from here at all; an MCP server can, so that one gets a button — but
     * only where a gateway exists to register it with.
     *
     * <p>This is the one place the catalog knows about MCP. A general
     * "sections may contribute an action here" mechanism would be the
     * nicer answer if a second such source ever turns up; one source does
     * not carry a plug-in point.
     */
    private List<UiAction> registerActions() {
        if (mcpRegistryAdmin.getIfAvailable() == null) {
            return List.of();
        }
        return List.of(UiAction.secondary("register-mcp", "Register MCP Server").icon("plug")
                .dispatch("GET", "/mcp-gateway/api/new"));
    }

    @GetMapping
    public UiPage list(@RequestParam(required = false) String q) {
        var byName = new java.util.LinkedHashMap<String, ToolCatalogComponent.Entry>();

        // Registry tools (built-in ToolFactory + MultiToolProvider), grouped
        // by their rubric — the registry's view is live, so dynamic providers
        // (e.g. workflows) reflect the current store.
        // Everything that exists, not everything that is switched on. The
        // Settings dialog hangs off a catalog row, so a disabled tool that
        // lost its row would have no way back short of editing the store by
        // hand. Read once: the settings live in one document.
        Map<String, ToolSettings> stored = storedSettings();
        sourceRegistry.toolNamesByGroup().forEach((group, names) -> {
            for (String name : names) {
                boolean enabled = stored.getOrDefault(name, ToolSettings.none()).enabledOrDefault();
                byName.put(name, describe(group, name, enabled));
            }
        });

        // Inline tools handled by AgentChatService (run_agent / run_agents) —
        // these have no ToolFactory, so their schema lives in the service, not
        // the registry. They are agent functions, so they join the "Agents"
        // rubric. (putIfAbsent so a registry entry would win, though today
        // there is no overlap.)
        for (var def : AgentChatService.inlineToolDefinitions()) {
            byName.putIfAbsent(def.name(), entryFromDefinition(def));
        }

        List<ToolCatalogComponent.Entry> entries = new ArrayList<>(byName.values());
        if (q != null && !q.isBlank()) {
            String needle = q.toLowerCase();
            entries.removeIf(e -> !(e.name().toLowerCase().contains(needle)
                    || (e.description() != null && e.description().toLowerCase().contains(needle))));
        }
        // Group is a lowercase machine namespace; sorting on it keeps rubrics
        // together, and the subgroup keeps one source's tools adjacent —
        // nulls first, so tools without a source stay at the top of their
        // group rather than after the collapsible sections.
        entries.sort(Comparator.comparing(ToolCatalogComponent.Entry::group)
                .thenComparing(ToolCatalogComponent.Entry::subgroup,
                        Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparing(ToolCatalogComponent.Entry::name));
        return new ToolListPage(entries, q, registerActions()).render();
    }

    /** The search field posts its form here; the response is the filtered catalog. */
    @PostMapping("/search")
    public UiPage search(@RequestBody Map<String, Object> raw) {
        return list(new FormBody(raw).str("q"));
    }

    /**
     * Opens the "Test tool" dialog as a patch over whatever is on screen —
     * the catalog page itself is untouched, so its expanded groups survive
     * opening (and closing) the dialog.
     */
    @GetMapping("/{name}/test")
    public ai.mindconnect.ui.model.UiPatch testDialog(@PathVariable String name) {
        return toolTestDialog(name, null, null);
    }

    /** Runs the tool with the supplied JSON arguments, re-rendering the dialog in place. */
    @PostMapping("/{name}/test")
    public ai.mindconnect.ui.model.UiPatch runTest(@PathVariable String name,
                                                   @RequestBody Map<String, Object> raw) {
        String argsJson = new FormBody(raw).str("arguments");
        var agentTool = AgentTool.of(name);
        ToolTestService.Result result = toolTestService.test(agentTool, argsJson);
        return toolTestDialog(name, argsJson, result);
    }

    /** Opens the settings dialog for one tool, over whatever is on screen. */
    @GetMapping("/{name}/settings")
    public ai.mindconnect.ui.model.UiPatch settingsDialog(@PathVariable String name) {
        return settingsDialog(name, null);
    }

    /**
     * Stores what an operator decided. An empty form is not an empty
     * description — it means "inherit", so blank fields become null and the
     * entry disappears when nothing is left.
     */
    @PostMapping("/{name}/settings")
    public ai.mindconnect.ui.model.UiPatch saveSettings(@PathVariable String name,
                                                        @RequestBody Map<String, Object> raw) {
        ToolRepository repository = toolRepository.getIfAvailable();
        if (repository == null) {
            return settingsDialog(name, "This installation stores no tool settings.");
        }
        FormBody body = new FormBody(raw);
        String description = body.str("description");
        // parametersFrom reads only what the form carried, so the dialog has
        // to render a field for every parameter that has something stored —
        // see ToolSettingsComponent. Saving is a replacement, and a
        // replacement is only honest about what it showed.
        ToolSettings settings = new ToolSettings(
                body.bool("enabled", true) ? null : Boolean.FALSE,   // enabled is the default; store only "off"
                description == null || description.isBlank() ? null : description.trim(),
                ToolSettingsComponent.parametersFrom(raw));
        repository.save(name, settings);
        log.info("tool settings for '{}' saved by the admin UI", name);
        return settingsDialog(name, settings.isEmpty()
                ? "Saved — nothing deviates from the defaults any more."
                : "Saved.");
    }

    /** Back to what the source says. */
    @DeleteMapping("/{name}/settings")
    public ai.mindconnect.ui.model.UiPatch resetSettings(@PathVariable String name) {
        ToolRepository repository = toolRepository.getIfAvailable();
        if (repository != null) {
            repository.delete(name);
        }
        return settingsDialog(name, "Reset — the tool describes itself again.");
    }

    @PostMapping("/settings-dialog/close")
    public ai.mindconnect.ui.model.UiPatch closeSettingsDialog() {
        return ai.mindconnect.ui.model.UiPatch.of()
                .patch(ai.mindconnect.ui.model.UiPatch.Operation.remove("tool-settings-dialog"));
    }

    /**
     * The dialog as a remove+append patch, like the test dialog: the page
     * behind it keeps its expanded groups.
     */
    private ai.mindconnect.ui.model.UiPatch settingsDialog(String name, String message) {
        ToolRepository repository = toolRepository.getIfAvailable();
        ToolSettings settings = repository == null
                ? ToolSettings.none()
                : repository.settings(name);

        // The source's own text, from the registry beneath the overlay. The
        // effective one would hand back the override and let the dialog
        // present it as the original — which is the one thing this dialog
        // must not do, since comparing against the original is why it shows
        // the text at all.
        String sourceDescription = null;
        Map<String, String> sourceParameters = new java.util.LinkedHashMap<>();
        try {
            var resolved = sourceRegistry.resolve(AgentTool.of(name), ToolCallScope.detached(null));
            if (resolved.isPresent()) {
                Tool tool = resolved.get();
                sourceDescription = tool.description();
                if (tool.parametersSchema() != null
                        && tool.parametersSchema().get("properties") instanceof Map<?, ?> properties) {
                    properties.forEach((parameter, definition) -> {
                        String text = definition instanceof Map<?, ?> fields
                                && fields.get("description") != null
                                ? String.valueOf(fields.get("description"))
                                : "";
                        sourceParameters.put(String.valueOf(parameter), text);
                    });
                }
            }
        } catch (RuntimeException e) {
            // A tool whose source cannot be built right now still has
            // settings worth editing — switching it off may be exactly what
            // an operator came here to do. Half a dialog beats a 500.
            log.debug("Tool '{}' has no resolvable source for its settings dialog: {}",
                    name, e.getMessage());
        }

        var component = new ToolSettingsComponent(name, settings, sourceDescription,
                sourceParameters, message);
        UiDialog dialog = UiDialog.of(component.title(), null, component.render());
        dialog.setId("tool-settings-dialog");
        return ai.mindconnect.ui.model.UiPatch.of()
                .patch(ai.mindconnect.ui.model.UiPatch.Operation.remove("tool-settings-dialog"))
                .patch(ai.mindconnect.ui.model.UiPatch.Operation.append("sui-dialogs", dialog));
    }

    /**
     * Close is just "remove the overlay" — the page behind stays as-is.
     * Shared by both tool-test dialogs (catalog and agent tools); the ×
     * and backdrop close client-side, this backs the dialog's Close button.
     */
    @PostMapping("/test-dialog/close")
    public ai.mindconnect.ui.model.UiPatch closeTestDialog() {
        return ai.mindconnect.ui.model.UiPatch.of()
                .patch(ai.mindconnect.ui.model.UiPatch.Operation.remove("tool-test-dialog"));
    }

    /**
     * The tool-test dialog as a remove+append patch on the body-level dialog
     * host (same pattern as the workflow admin): remove is a no-op on first
     * open and replaces the modal in place on a re-render; a null close-href
     * just removes the overlay without navigating.
     */
    private ai.mindconnect.ui.model.UiPatch toolTestDialog(String name, String previousJson,
                                                           ToolTestService.Result result) {
        var component = new ToolCatalogTestComponent(
                name, toolRegistry, previousJson, result);
        UiDialog dialog = UiDialog.of(component.title(), null, component.render());
        dialog.setId("tool-test-dialog");
        return ai.mindconnect.ui.model.UiPatch.of()
                .patch(ai.mindconnect.ui.model.UiPatch.Operation.remove("tool-test-dialog"))
                .patch(ai.mindconnect.ui.model.UiPatch.Operation.append("sui-dialogs", dialog));
    }

    /** Builds a catalog entry straight from an inline {@link ai.mindconnect.llm.domain.ToolDefinition}. */
    private ToolCatalogComponent.Entry entryFromDefinition(
            ai.mindconnect.llm.domain.ToolDefinition def) {
        return new ToolCatalogComponent.Entry("agents", def.name(), def.description(),
                def.parametersSchema(), null);
    }

    /**
     * Resolves a tool by name to read its description + parameters schema.
     * Uses a throwaway {@link AgentTool} reference (no overrides) and a null
     * user/session — the same shape the per-agent tool form uses. Tools that
     * can't resolve (e.g. {@code run_agent}, which is handled inline and not
     * in the registry, or a tool whose dependencies are unavailable) still get
     * a row, just without schema details.
     */
    private ToolCatalogComponent.Entry describe(String group, String name, boolean enabled) {
        AgentTool ref = AgentTool.of(name);
        Object overrides = toolRegistry.overridesSchema(name);
        String subgroup = toolRegistry.subgroupOf(name);
        try {
            // What the model is told, so the row shows what is in force. A
            // switched-off tool has no effective form at all, so it is shown
            // as its source defines it — an empty row is no help to whoever
            // came to switch it back on.
            ToolRegistry registry = enabled ? toolRegistry : sourceRegistry;
            var resolved = registry.resolve(ref, ToolCallScope.detached(null));
            if (resolved.isPresent()) {
                Tool tool = resolved.get();
                return new ToolCatalogComponent.Entry(group, subgroup, name, tool.description(),
                        tool.parametersSchema(), overrides, enabled);
            }
        } catch (Exception e) {
            log.debug("Tool '{}' could not be resolved for catalog: {}", name, e.getMessage());
        }
        return new ToolCatalogComponent.Entry(group, subgroup, name, null, null, overrides, enabled);
    }

    /** Read in one go: the settings are one document, not one per tool. */
    private Map<String, ToolSettings> storedSettings() {
        ToolRepository repository = toolRepository.getIfAvailable();
        return repository == null ? Map.of() : repository.all();
    }
}
