package ai.mindconnect.adminui.ui.controller;


import ai.mindconnect.adminui.service.ToolTestService;
import ai.mindconnect.adminui.ui.AdminLayoutFactory;
import ai.mindconnect.adminui.ui.component.ToolCatalogComponent;
import ai.mindconnect.adminui.ui.component.ToolCatalogTestComponent;
import ai.mindconnect.adminui.ui.page.ToolListPage;
import ai.mindconnect.agent.tool.AgentTool;
import ai.mindconnect.agent.tool.Tool;
import ai.mindconnect.agent.tool.ToolRegistry;
import ai.mindconnect.common.Namespace;
import ai.mindconnect.ui.model.UiDialog;
import ai.mindconnect.ui.model.UiPage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
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
import java.util.UUID;

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
    private final Namespace defaultNamespace;
    private final ToolTestService toolTestService;
    private final AdminLayoutFactory layoutFactory;

    public ToolCatalogUiController(ToolRegistry toolRegistry, Namespace defaultNamespace,
                                 ToolTestService toolTestService,
                                 AdminLayoutFactory layoutFactory) {
        this.toolRegistry = toolRegistry;
        this.defaultNamespace = defaultNamespace;
        this.toolTestService = toolTestService;
        this.layoutFactory = layoutFactory;
    }

    @GetMapping
    public UiPage list(@RequestParam(required = false) String q) {
        var byName = new java.util.LinkedHashMap<String, ToolCatalogComponent.Entry>();

        // Registry tools (built-in ToolFactory + MultiToolProvider), grouped
        // by their rubric — the registry's view is live, so dynamic providers
        // (e.g. workflows) reflect the current store.
        toolRegistry.toolNamesByGroup().forEach((group, names) -> {
            for (String name : names) {
                byName.put(name, describe(group, name));
            }
        });

        // Inline tools handled by AgentChatService (run_agent / run_agents) —
        // these have no ToolFactory, so their schema lives in the service, not
        // the registry. They are agent functions, so they join the "Agents"
        // rubric. (putIfAbsent so a registry entry would win, though today
        // there is no overlap.)
        for (var def : ai.mindconnect.agent.service.AgentChatService.inlineToolDefinitions()) {
            byName.putIfAbsent(def.name(), entryFromDefinition(def));
        }

        List<ToolCatalogComponent.Entry> entries = new ArrayList<>(byName.values());
        if (q != null && !q.isBlank()) {
            String needle = q.toLowerCase();
            entries.removeIf(e -> !(e.name().toLowerCase().contains(needle)
                    || (e.description() != null && e.description().toLowerCase().contains(needle))));
        }
        // Group is a lowercase machine namespace; sorting on it keeps rubrics together.
        entries.sort(Comparator.comparing(ToolCatalogComponent.Entry::group)
                .thenComparing(ToolCatalogComponent.Entry::name));
        return new ToolListPage(entries, q).render();
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
        var agentTool = AgentTool.of(new UUID(0, 0), name);
        ToolTestService.Result result = toolTestService.test(defaultNamespace, agentTool, argsJson);
        return toolTestDialog(name, argsJson, result);
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
                name, defaultNamespace, toolRegistry, previousJson, result);
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
    private ToolCatalogComponent.Entry describe(String group, String name) {
        AgentTool ref = AgentTool.of(UUID.randomUUID(), name);
        Object overrides = toolRegistry.overridesSchema(name);
        try {
            var resolved = toolRegistry.resolve(ref, defaultNamespace, null, null);
            if (resolved.isPresent()) {
                Tool tool = resolved.get();
                return new ToolCatalogComponent.Entry(group, name, tool.description(),
                        tool.parametersSchema(), overrides);
            }
        } catch (Exception e) {
            log.debug("Tool '{}' could not be resolved for catalog: {}", name, e.getMessage());
        }
        return new ToolCatalogComponent.Entry(group, name, null, null, overrides);
    }
}
