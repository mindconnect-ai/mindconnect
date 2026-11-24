package ai.mindconnect.adminui.ui.controller;

import ai.mindconnect.adminui.service.ToolTestService;
import ai.mindconnect.adminui.ui.component.AgentSetting;
import ai.mindconnect.adminui.ui.component.ToolTestComponent;
import ai.mindconnect.adminui.ui.page.ToolBundleFormPage;
import ai.mindconnect.adminui.ui.page.ToolDetailPage;
import ai.mindconnect.adminui.ui.page.ToolFormPage;
import ai.mindconnect.agent.AgentId;
import ai.mindconnect.agent.management.api.AgentManagement;
import ai.mindconnect.agent.management.api.ManagedAgent;
import ai.mindconnect.agent.sdk.AgentRuntime;
import ai.mindconnect.agent.tool.AgentTool;
import ai.mindconnect.agent.tool.AgentToolId;
import ai.mindconnect.agent.tool.ToolBundles;
import ai.mindconnect.agent.tool.ToolRegistry;
import ai.mindconnect.chatui.ui.controller.FormBody;
import ai.mindconnect.common.DomainException;
import ai.mindconnect.ui.model.UiDialog;
import ai.mindconnect.ui.model.UiPage;
import ai.mindconnect.ui.model.UiPatch;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.ConcurrentModificationException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;

/**
 * The tools of an agent: adding one or a set, looking at one, changing,
 * testing and removing it. Ids in paths are their plain values.
 */
@RestController
@RequestMapping("/admin/api/agents")
public class AgentToolUiController {

    /** How often a change to an agent's tools is made again when another save came in between. */
    private static final int ATTEMPTS = 3;

    /** The runtime's: the pages keep no way of their own to the agents. */
    private final AgentManagement agents;
    private final ToolRegistry toolRegistry;
    private final ToolTestService toolTestService;
    private final AgentForm form;
    private final AgentDetailView detail;

    public AgentToolUiController(AgentRuntime runtime, ToolRegistry toolRegistry, ToolTestService toolTestService,
                                 ObjectMapper objectMapper, AgentDetailView detail) {
        this.agents = runtime.service(AgentManagement.class);
        this.toolRegistry = toolRegistry;
        this.toolTestService = toolTestService;
        this.form = new AgentForm(objectMapper);
        this.detail = detail;
    }

    @GetMapping("/{id}/tools/new")
    public ResponseEntity<UiPage> newToolForm(@PathVariable("id") String idValue) {
        return agents.find(AgentId.of(idValue))
                .map(a -> ResponseEntity.ok(new ToolFormPage(a, null, toolRegistry).render()))
                .orElse(ResponseEntity.notFound().build());
    }

    /** The form that adds tools by the set — a group, a subgroup, everything on a connection. */
    @GetMapping("/{id}/tools/new-group")
    public ResponseEntity<UiPage> newToolBundleForm(@PathVariable("id") String idValue) {
        return agents.find(AgentId.of(idValue))
                .map(a -> ResponseEntity.ok(new ToolBundleFormPage(a, bundles()).render()))
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Expands what was picked and adds each tool as a row of its own, skipping
     * names the agent already has — adding the email group twice must not
     * give the model two of everything.
     */
    @PostMapping("/{id}/tools/bundle")
    public ResponseEntity<UiPage> addToolBundle(@PathVariable("id") String idValue,
                                                @RequestBody Map<String, Object> raw,
                                                @AuthenticationPrincipal OidcUser user) {
        AgentId id = AgentId.of(idValue);
        FormBody body = new FormBody(raw);
        List<String> names = bundles().expand(body.strList("bundles"));
        boolean needsApproval = Boolean.TRUE.equals(body.bool("needsApproval"));
        boolean deferred = Boolean.TRUE.equals(body.bool("deferred"));
        return agents.find(id)
                .map(a -> {
                    updateTools(id, tools -> {
                        Set<String> present = new java.util.HashSet<>();
                        tools.forEach(t -> present.add(t.name()));
                        List<AgentTool> next = new ArrayList<>(tools);
                        for (String name : names) {
                            if (present.add(name)) {
                                next.add(new AgentTool(AgentToolId.random(), name, null, Map.of(),
                                        true, deferred, needsApproval, null));
                            }
                        }
                        return next;
                    });
                    return detail.page(idValue, "tools", null, user);
                })
                .orElse(ResponseEntity.notFound().build());
    }

    private ToolBundles bundles() {
        return ToolBundles.of(toolRegistry, name -> !AgentSetting.DERIVED_TOOLS.contains(name));
    }

    @GetMapping("/{id}/tools/{toolId}")
    public ResponseEntity<UiPage> viewTool(@PathVariable("id") String idValue,
                                           @PathVariable("toolId") String toolIdValue) {
        AgentId id = AgentId.of(idValue);
        AgentToolId toolId = AgentToolId.of(toolIdValue);
        var agentOpt = agents.find(id);
        if (agentOpt.isEmpty()) return ResponseEntity.notFound().build();
        var a    = agentOpt.get();
        var tool = AgentSetting.tools(a).stream().filter(t -> t.id().equals(toolId)).findFirst().orElse(null);
        if (tool == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(new ToolDetailPage(a, tool, toolRegistry).render());
    }

    @GetMapping("/{id}/tools/{toolId}/edit")
    public ResponseEntity<UiPage> editToolForm(@PathVariable("id") String idValue,
                                               @PathVariable("toolId") String toolIdValue) {
        AgentId id = AgentId.of(idValue);
        AgentToolId toolId = AgentToolId.of(toolIdValue);
        var agentOpt = agents.find(id);
        if (agentOpt.isEmpty()) return ResponseEntity.notFound().build();
        var a    = agentOpt.get();
        var tool = AgentSetting.tools(a).stream().filter(t -> t.id().equals(toolId)).findFirst().orElse(null);
        if (tool == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(new ToolFormPage(a, tool, toolRegistry).render());
    }

    /**
     * Opens the "Test tool" dialog as a patch over whatever is on screen —
     * the page underneath (agent detail or tool detail) is untouched, so
     * its state survives opening and closing the dialog.
     */
    @GetMapping("/{id}/tools/{toolId}/test")
    public ResponseEntity<UiPatch> testToolDialog(@PathVariable("id") String idValue,
                                                  @PathVariable("toolId") String toolIdValue) {
        AgentId id = AgentId.of(idValue);
        AgentToolId toolId = AgentToolId.of(toolIdValue);
        var agentOpt = agents.find(id);
        if (agentOpt.isEmpty()) return ResponseEntity.notFound().build();
        var a    = agentOpt.get();
        var tool = AgentSetting.tools(a).stream().filter(t -> t.id().equals(toolId)).findFirst().orElse(null);
        if (tool == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(toolTestDialog(a, tool, null, null));
    }

    /**
     * Executes the tool with the supplied JSON arguments. Re-renders the
     * dialog in place with the outcome underneath the form so the admin
     * can read the result and optionally re-send with tweaked args.
     */
    @PostMapping("/{id}/tools/{toolId}/test")
    public ResponseEntity<UiPatch> runToolTest(@PathVariable("id") String idValue,
                                               @PathVariable("toolId") String toolIdValue,
                                               @RequestBody Map<String, Object> raw) {
        AgentId id = AgentId.of(idValue);
        AgentToolId toolId = AgentToolId.of(toolIdValue);
        var agentOpt = agents.find(id);
        if (agentOpt.isEmpty()) return ResponseEntity.notFound().build();
        var a    = agentOpt.get();
        var tool = AgentSetting.tools(a).stream().filter(t -> t.id().equals(toolId)).findFirst().orElse(null);
        if (tool == null) return ResponseEntity.notFound().build();
        var body = new FormBody(raw);
        String argsJson = body.str("arguments");
        ToolTestService.Result result = toolTestService.test(tool, argsJson);
        return ResponseEntity.ok(toolTestDialog(a, tool, argsJson, result));
    }

    /**
     * The tool-test dialog as a remove+append patch on the body-level dialog
     * host (same pattern as the workflow admin): remove is a no-op on first
     * open and replaces the modal in place on a re-render; a null close-href
     * just removes the overlay without navigating.
     */
    private UiPatch toolTestDialog(ManagedAgent agent, AgentTool tool,
                                   String previousJson,
                                   ToolTestService.Result result) {
        var component = new ToolTestComponent(agent, tool, toolRegistry, previousJson, result);
        UiDialog dialog = UiDialog.of(component.title(), null, component.render());
        dialog.setId("tool-test-dialog");
        return UiPatch.of()
                .patch(UiPatch.Operation.remove("tool-test-dialog"))
                .patch(UiPatch.Operation.append("sui-dialogs", dialog));
    }

    @PostMapping("/{id}/tools")
    public ResponseEntity<UiPage> addTool(@PathVariable("id") String idValue,
                                          @RequestBody Map<String, Object> raw,
                                          @AuthenticationPrincipal OidcUser user) {
        AgentId id = AgentId.of(idValue);
        return agents.find(id)
                .map(a -> {
                    AgentTool tool = form.tool(AgentToolId.random(), new FormBody(raw));
                    updateTools(id, tools -> {
                        List<AgentTool> next = new ArrayList<>(tools);
                        next.add(tool);
                        return next;
                    });
                    return detail.page(idValue, "tools", tool.id().value(), user);
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @PutMapping("/{id}/tools/{toolId}")
    public ResponseEntity<UiPage> updateTool(@PathVariable("id") String idValue,
                                             @PathVariable("toolId") String toolIdValue,
                                             @RequestBody Map<String, Object> raw,
                                             @AuthenticationPrincipal OidcUser user) {
        AgentId id = AgentId.of(idValue);
        AgentToolId toolId = AgentToolId.of(toolIdValue);
        return agents.find(id)
                .map(a -> {
                    AgentTool updated = form.tool(toolId, new FormBody(raw));
                    updateTools(id, tools -> tools.stream()
                            .map(t -> t.id().equals(toolId) ? updated : t)
                            .toList());
                    return detail.page(idValue, "tools", toolId.value(), user);
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}/tools/{toolId}")
    public ResponseEntity<UiPatch> deleteTool(@PathVariable("id") String idValue,
                                              @PathVariable("toolId") String toolIdValue,
                                              @AuthenticationPrincipal OidcUser user) {
        AgentId id = AgentId.of(idValue);
        AgentToolId toolId = AgentToolId.of(toolIdValue);
        String userId = user.getPreferredUsername();
        return agents.find(id)
                .map(a -> {
                    ManagedAgent updated = updateTools(id, tools -> tools.stream()
                            .filter(t -> !t.id().equals(toolId)).toList());
                    return ResponseEntity.ok(detail.of(updated, user).refreshTools());
                })
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Replaces the agent's tools with what {@code change} makes of the stored
     * list — adding, editing or removing one tool without dropping a tool
     * that was added meanwhile. {@code change} may run more than once.
     */
    private ManagedAgent updateTools(AgentId id, UnaryOperator<List<AgentTool>> change) {
        for (int attempt = 1; ; attempt++) {
            ManagedAgent read = agents.find(id).orElseThrow(() -> DomainException.notFound("Agent", id.value()));
            try {
                return agents.update(id, read.version(), stored -> (
                        AgentSetting.withTools(stored, change.apply(AgentSetting.tools(stored)))));
            } catch (ConcurrentModificationException e) {
                if (attempt >= ATTEMPTS) throw e;
            }
        }
    }
}
