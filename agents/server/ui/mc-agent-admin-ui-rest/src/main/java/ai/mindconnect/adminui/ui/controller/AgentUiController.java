package ai.mindconnect.adminui.ui.controller;

import java.util.Set;
import ai.mindconnect.adminui.ui.page.ToolBundleFormPage;
import ai.mindconnect.agent.tool.ToolBundles;
import ai.mindconnect.adminui.service.ToolTestService;
import ai.mindconnect.adminui.ui.component.AgentFormComponent;
import ai.mindconnect.adminui.ui.component.ToolTestComponent;
import ai.mindconnect.adminui.ui.page.AgentDetailPage;
import ai.mindconnect.adminui.ui.page.AgentFormPage;
import ai.mindconnect.adminui.ui.page.AgentListPage;
import ai.mindconnect.adminui.ui.page.ToolDetailPage;
import ai.mindconnect.adminui.ui.page.ToolFormPage;
import ai.mindconnect.adminui.ui.component.AgentSetting;
import ai.mindconnect.agent.SessionId;
import ai.mindconnect.agent.management.api.AgentId;
import ai.mindconnect.agent.management.api.AgentManagement;
import ai.mindconnect.agent.management.api.ManagedAgent;
import ai.mindconnect.agent.management.api.SessionManagement;
import ai.mindconnect.agent.runtime.api.domain.AgentDefinition;
import ai.mindconnect.agent.runtime.api.domain.ModelSettings;
import ai.mindconnect.agent.runtime.capabilities.memory.MemoryPlugin;
import ai.mindconnect.agent.runtime.capabilities.review.ResponseReviewPlugin;
import ai.mindconnect.agent.runtime.capabilities.skill.SkillPlugin;
import ai.mindconnect.agent.runtime.capabilities.subagent.SubAgentPlugin;
import ai.mindconnect.agent.sdk.AgentRuntime;
import ai.mindconnect.agent.tool.AgentTool;
import ai.mindconnect.agent.tool.AgentToolId;
import ai.mindconnect.agent.tool.ToolRegistry;
import ai.mindconnect.llm.port.out.LlmConfigRepository;
import ai.mindconnect.chatui.service.SessionOwnership;
import ai.mindconnect.chatui.ui.controller.FormBody;
import ai.mindconnect.common.DomainException;
import ai.mindconnect.ui.model.UiDialog;
import ai.mindconnect.ui.model.UiPage;
import ai.mindconnect.ui.model.UiPatch;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.ConcurrentModificationException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.UnaryOperator;

/**
 * The agent screens. Ids in paths are their plain values.
 */
@RestController
@RequestMapping("/admin/api/agents")
public class AgentUiController {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** How often a change to an agent's tools is made again when another save came in between. */
    private static final int ATTEMPTS = 3;

    /** The runtime's: the pages keep no way of their own to the agents. */
    private final AgentManagement agents;
    /** The runtime's too: an agent's sessions are listed and deleted where every session is. */
    private final SessionManagement sessions;
    private final LlmConfigRepository llmConfigRepository;
    private final ToolRegistry toolRegistry;
    private final ToolTestService toolTestService;
    private final ObjectMapper objectMapper;
    /** Fills the form's skills picker; optional, like the skills screen's own store. */
    private final org.springframework.beans.factory.ObjectProvider<
            ai.mindconnect.agent.skill.port.out.SkillRepository> skillCatalog;

    /** The "Memory" tab — what the agent keeps about its users; optional like the memory itself. */
    private final org.springframework.beans.factory.ObjectProvider<MemoryAdminUiController> memoryUi;

    public AgentUiController(AgentRuntime runtime,
                                LlmConfigRepository llmConfigRepository,
                                ToolRegistry toolRegistry,
                                ToolTestService toolTestService,
                                ObjectMapper objectMapper,
                                org.springframework.beans.factory.ObjectProvider<
                                        ai.mindconnect.agent.skill.port.out.SkillRepository> skillCatalog,
                                org.springframework.beans.factory.ObjectProvider<MemoryAdminUiController> memoryUi) {
        this.memoryUi = memoryUi;
        this.agents = runtime.service(AgentManagement.class);
        this.sessions = runtime.service(SessionManagement.class);
        this.llmConfigRepository = llmConfigRepository;
        this.toolRegistry = toolRegistry;
        this.toolTestService = toolTestService;
        this.objectMapper = objectMapper;
        this.skillCatalog = skillCatalog;
    }

    @GetMapping
    public UiPage list(@RequestParam(required = false) String q) {
        return new AgentListPage(filterAgents(agents.list(), q), q).render();
    }

    /** The search field posts its form here; the response is the filtered list. */
    @PostMapping("/search")
    public UiPage search(@RequestBody Map<String, Object> raw) {
        return list(new FormBody(raw).str("q"));
    }

    /** Case-insensitive contains on name and description. */
    private static List<ManagedAgent> filterAgents(List<ManagedAgent> all, String q) {
        if (q == null || q.isBlank()) return all;
        String needle = q.toLowerCase();
        return all.stream()
                .filter(a -> a.definition().name().toLowerCase().contains(needle)
                        || a.definition().description().toLowerCase().contains(needle))
                .toList();
    }

    @GetMapping("/new")
    public UiPage newForm() {
        return form(null).render();
    }

    /** The form of an agent, or of a new one for {@code null}, with every agent there is to pick from. */
    private AgentFormPage form(ManagedAgent agent) {
        return new AgentFormPage(agent, llmConfigRepository, agents.list(), objectMapper, skills());
    }

    @GetMapping("/{id}")
    public ResponseEntity<UiPage> detail(@PathVariable("id") String idValue,
                                         @RequestParam(required = false) String section,
                                         @RequestParam(required = false) String row,
                                         @AuthenticationPrincipal OidcUser user) {
        String userId = user.getPreferredUsername();
        return agents.find(AgentId.of(idValue))
                .map(a -> ResponseEntity.ok(new AgentDetailPage(a, userId, sessions, section, row)
                        .memoryTab(memoryTab(a)).render()))
                .orElse(ResponseEntity.notFound().build());
    }

    private ai.mindconnect.ui.model.UiNode memoryTab(ManagedAgent agent) {
        MemoryAdminUiController memory = memoryUi.getIfAvailable();
        return memory == null ? null : memory.agentTab(ai.mindconnect.agent.AgentId.of(agent.id().value()),
                AgentSetting.tools(agent)).orElse(null);
    }

    @DeleteMapping("/{id}/sessions/{sessionId}")
    public ResponseEntity<UiPatch> deleteSession(@PathVariable("id") String idValue,
                                                 @PathVariable("sessionId") String sessionIdValue,
                                                 @AuthenticationPrincipal OidcUser user) {
        String userId = user.getPreferredUsername();
        var sessionId = ai.mindconnect.agent.runtime.api.domain.SessionId.of(sessionIdValue);
        // The tab lists the caller's sessions only; someone else's is answered like a missing one.
        if (sessions.find(sessionId).filter(session -> SessionOwnership.owns(session, user)).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        // Through the management: what else is kept of the session goes with it.
        sessions.delete(sessionId);
        return agents.find(AgentId.of(idValue))
                .map(a -> ResponseEntity.ok(new AgentDetailPage(a, userId, sessions).refreshSessions()))
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/{id}/edit")
    public ResponseEntity<UiPage> editForm(@PathVariable("id") String idValue) {
        return agents.find(AgentId.of(idValue))
                .map(a -> ResponseEntity.ok(form(a).render()))
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody Map<String, Object> raw,
                                    @AuthenticationPrincipal OidcUser user) {
        var body = new FormBody(raw);
        String name = body.str("name");
        if (name == null || name.isBlank()) {
            throw DomainException.invalid("An agent needs a name");
        }
        if (agents.findByName(name).isPresent()) {
            return ResponseEntity.ok(VersionedForms.nameTaken("Agent", name));
        }
        ManagedAgent created;
        try {
            created = agents.create(fromForm(ManagedAgent.of(new AgentDefinition(name, "", "",
                    ModelSettings.NONE, List.of(), 10, Map.of())), body));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        }
        // Only now is there an id to write into the definition.
        agents.update(created.id(), created.version(), java.util.function.UnaryOperator.identity());
        return detail(created.id().value(), null, null, user);
    }

    /**
     * Saves the edit form against the version it was opened with; when the agent
     * was saved since, the form stays on screen with a toast instead.
     */
    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable("id") String idValue,
                                    @RequestBody Map<String, Object> raw,
                                    @AuthenticationPrincipal OidcUser user) {
        AgentId id = AgentId.of(idValue);
        var body = new FormBody(raw);
        ManagedAgent existing = agents.find(id).orElse(null);
        if (existing == null) {
            return ResponseEntity.notFound().build();
        }
        ManagedAgent changed;
        try {
            changed = (fromForm(existing, body));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        }
        String name = changed.definition().name();
        if (agents.findByName(name).filter(other -> !other.id().equals(id)).isPresent()) {
            return ResponseEntity.ok(VersionedForms.nameTaken("Agent", name));
        }
        // A form without a version is one that checks nothing: it saves over what is stored now.
        Long opened = VersionedForms.version(body);
        try {
            agents.update(id, opened == null ? existing.version() : opened, stored -> changed);
            return detail(idValue, null, null, user);
        } catch (ConcurrentModificationException e) {
            return ResponseEntity.ok(VersionedForms.changedMeanwhile("Agent '" + existing.definition().name() + "'"));
        }
    }

    /**
     * The agent with what the form says. A field the form did not send is
     * left as it is; the tools are not the form's.
     *
     * @throws IllegalArgumentException for a memory setting that is no JSON
     *         object or names a strategy there is none of
     */
    private ManagedAgent fromForm(ManagedAgent agent, FormBody body) {
        AgentDefinition stored = agent.definition();
        Map<String, Object> metadata = new HashMap<>(stored.metadata());
        metadata.put(SubAgentPlugin.SUB_AGENTS, body.strList("callableAgents"));
        metadata.put(ResponseReviewPlugin.REVIEWERS, body.strList("responseReviewers"));
        // Every skill is no entry at all, none is an empty list, and the ones ticked are the list.
        switch (AgentFormComponent.skillsMode(body.str(AgentFormComponent.SKILLS_MODE))) {
            case AgentSetting.NONE -> metadata.put(SkillPlugin.SKILLS, List.of());
            case AgentSetting.SPECIFIC -> metadata.put(SkillPlugin.SKILLS, body.strList("skills"));
            default -> metadata.remove(SkillPlugin.SKILLS);
        }
        memoryFromForm(body).ifPresent(memory -> metadata.put(MemoryPlugin.MEMORY, memory));
        ModelSettings model = stored.modelSettings();
        String llm = body.str("llmConfigName");
        AgentDefinition definition = new AgentDefinition(
                sent(body.str("name"), stored.name()),
                sent(body.str("description"), stored.description()),
                sent(body.str("systemPrompt"), stored.instructions()),
                // The name of an LLM configuration is the model's name to the gateway.
                llm == null ? model : new ModelSettings(llm.isBlank() ? null : llm, model.temperature(),
                        model.maxOutputTokens(), model.reasoningEffort(), model.extra()),
                stored.tools(),
                body.num("maxIterations", stored.maxRounds()),
                metadata);
        String group = body.str("group");
        String icon = body.str("icon");
        return agent.withDefinition(definition).shownAs(
                group == null ? agent.group() : AgentSetting.machineName(group),
                icon == null ? agent.icon() : AgentSetting.machineName(icon),
                sent(body.str("welcomeMessage"), agent.welcomeMessage()));
    }

    private static String sent(String value, String otherwise) {
        return value == null ? otherwise : value;
    }

    /**
     * The form's Memory (JSON) field: blank keeps the stored setting, content
     * must be a JSON object the memory capability can read.
     */
    private Optional<Map<String, Object>> memoryFromForm(FormBody body) {
        String json = body.str("memoryConfig");
        if (json == null || json.isBlank()) return Optional.empty();
        Map<String, Object> memory;
        try {
            memory = objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid memory config: " + e.getMessage(), e);
        }
        MemoryPlugin.check(memory);
        return Optional.of(memory);
    }

    /**
     * The skills mode changed: re-render the skills picker under it, shown
     * only for SPECIFIC. The names the admin already ticked ride along in
     * the submitted form body, so switching away and back loses nothing.
     */
    @PostMapping("/skills-field")
    public UiPatch skillsField(@RequestParam(value = "id", required = false) String idValue,
                               @RequestBody Map<String, Object> raw) {
        var body = new FormBody(raw);
        ManagedAgent agent = idValue == null || idValue.isBlank() ? null
                : agents.find(AgentId.of(idValue)).orElse(null);
        var form = new AgentFormComponent(agent, llmConfigRepository, agents.list(), objectMapper, skills());
        return UiPatch.of().patch(UiPatch.Operation.replace("skills",
                form.skillsField(AgentFormComponent.skillsMode(body.str(AgentFormComponent.SKILLS_MODE)),
                        body.strList("skills"))));
    }

    /** The skill store this host wires, or null when it wires none. */
    private ai.mindconnect.agent.skill.port.out.SkillRepository skills() {
        return skillCatalog.getIfAvailable();
    }

    @PostMapping("/{id}/copy")
    public ResponseEntity<UiPage> copy(@PathVariable("id") String idValue) {
        AgentId id = AgentId.of(idValue);
        if (agents.find(id).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        ManagedAgent copy = agents.copy(id);
        // The copy is an agent of its own: its id in its definition, not the original's.
        return ResponseEntity.ok(form(agents.update(copy.id(), copy.version(), java.util.function.UnaryOperator.identity()))
                .render());
    }

    // ── Tool endpoints ──────────────────────────────────────────────────────

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
                    return detail(idValue, "tools", null, user);
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
                    AgentTool tool = toolFromBody(AgentToolId.random(), new FormBody(raw));
                    updateTools(id, tools -> {
                        List<AgentTool> next = new ArrayList<>(tools);
                        next.add(tool);
                        return next;
                    });
                    return detail(idValue, "tools", tool.id().value(), user);
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
                    AgentTool updated = toolFromBody(toolId, new FormBody(raw));
                    updateTools(id, tools -> tools.stream()
                            .map(t -> t.id().equals(toolId) ? updated : t)
                            .toList());
                    return detail(idValue, "tools", toolId.value(), user);
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
                    return ResponseEntity.ok(new AgentDetailPage(updated, userId, sessions).refreshTools());
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

    /** The tool the form describes, under {@code toolId} — a fresh id for a new tool. */
    private AgentTool toolFromBody(AgentToolId toolId, FormBody body) {
        // Name: prefer explicit custom name, fall back to selected builtin name.
        String builtin = body.str("builtinName");
        String name = body.str("name");
        if (name == null || name.isBlank()) name = builtin;
        if (name == null || name.equals("custom")) name = "";
        Map<String, Object> overrides;
        try {
            // Accept both the new "overrides" field and the legacy "toolConfig" field
            // so a partially-deployed admin UI doesn't lose data on save.
            String json = body.str("overrides");
            if (json == null || json.isBlank()) json = body.str("toolConfig");
            overrides = (json != null && !json.isBlank())
                    ? MAPPER.readValue(json, new TypeReference<>() {})
                    : Map.of();
        } catch (Exception e) {
            overrides = Map.of();
        }
        // Renamed builtin: the custom name is what the LLM sees; the alias
        // override keeps resolution pointing at the selected registry tool.
        if (builtin != null && !builtin.isBlank() && !builtin.equals("custom")
                && !builtin.equals(name) && !overrides.containsKey("tool")) {
            overrides = new java.util.LinkedHashMap<>(overrides);
            overrides.put("tool", builtin);
        }
        boolean deferred = Boolean.TRUE.equals(body.bool("deferred"));
        boolean needsApproval = Boolean.TRUE.equals(body.bool("needsApproval"));
        // Blank/invalid → null → no per-tool cap (the runtime safety cap still applies).
        Integer maxResultChars = null;
        String maxChars = body.str("maxResultChars");
        if (maxChars != null && !maxChars.isBlank()) {
            try {
                int parsed = Integer.parseInt(maxChars.trim());
                if (parsed > 0) maxResultChars = parsed;
            } catch (NumberFormatException ignored) {
            }
        }
        // A tool an agent names is one it may use: there is no switching one off, only removing it.
        return new AgentTool(toolId, name, body.str("description"), overrides, true, deferred,
                needsApproval, maxResultChars);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<UiPage> delete(@PathVariable("id") String idValue) {
        AgentId id = AgentId.of(idValue);
        return agents.find(id)
                .map(existing -> {
                    agents.delete(id);
                    return ResponseEntity.ok(new AgentListPage(agents.list()).render());
                })
                .orElse(ResponseEntity.notFound().build());
    }
}
