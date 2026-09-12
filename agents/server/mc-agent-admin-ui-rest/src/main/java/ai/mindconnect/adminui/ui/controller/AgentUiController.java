package ai.mindconnect.adminui.ui.controller;

import ai.mindconnect.adminui.service.ToolTestService;
import ai.mindconnect.adminui.ui.component.ToolTestComponent;
import ai.mindconnect.adminui.ui.page.AgentDetailPage;
import ai.mindconnect.adminui.ui.page.AgentFormPage;
import ai.mindconnect.adminui.ui.page.AgentListPage;
import ai.mindconnect.adminui.ui.page.ToolDetailPage;
import ai.mindconnect.adminui.ui.page.ToolFormPage;
import ai.mindconnect.agent.AgentId;
import ai.mindconnect.agent.SessionId;
import ai.mindconnect.agent.runtime.domain.AgentDefinition;
import ai.mindconnect.agent.runtime.domain.AgentPatch;
import ai.mindconnect.agent.runtime.domain.AgentSpec;
import ai.mindconnect.agent.tool.AgentTool;
import ai.mindconnect.agent.tool.AgentToolId;
import ai.mindconnect.agent.tool.ToolRegistry;
import ai.mindconnect.agent.runtime.port.out.AgentDefinitionRepository;
import ai.mindconnect.agent.runtime.port.out.AgentSessionRepository;
import ai.mindconnect.agent.runtime.service.AgentRegistryService;
import ai.mindconnect.llm.port.out.LlmConfigRepository;
import ai.mindconnect.chatui.ui.controller.FormBody;
import ai.mindconnect.agent.runtime.memory.domain.MemoryConfig;
import ai.mindconnect.common.StaleVersionException;
import ai.mindconnect.ui.model.UiDialog;
import ai.mindconnect.ui.model.UiPage;
import ai.mindconnect.ui.model.UiPatch;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.val;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The agent screens. Ids in paths are their plain values.
 */
@RestController
@RequestMapping("/admin/api/agents")
public class AgentUiController {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AgentRegistryService registryService;
    private final AgentDefinitionRepository repository;
    private final AgentSessionRepository sessionRepository;
    private final LlmConfigRepository llmConfigRepository;
    private final ToolRegistry toolRegistry;
    private final ToolTestService toolTestService;
    private final ObjectMapper objectMapper;
    /** Fills the form's skills picker; optional, like the skills screen's own store. */
    private final org.springframework.beans.factory.ObjectProvider<
            ai.mindconnect.agent.runtime.skill.SkillCatalog> skillCatalog;

    public AgentUiController(AgentRegistryService registryService,
                                AgentDefinitionRepository repository,
                                AgentSessionRepository sessionRepository,
                                LlmConfigRepository llmConfigRepository,
                                ToolRegistry toolRegistry,
                                ToolTestService toolTestService,
                                ObjectMapper objectMapper,
                                org.springframework.beans.factory.ObjectProvider<
                                        ai.mindconnect.agent.runtime.skill.SkillCatalog> skillCatalog) {
        this.registryService = registryService;
        this.repository = repository;
        this.sessionRepository = sessionRepository;
        this.llmConfigRepository = llmConfigRepository;
        this.toolRegistry = toolRegistry;
        this.toolTestService = toolTestService;
        this.objectMapper = objectMapper;
        this.skillCatalog = skillCatalog;
    }

    @GetMapping
    public UiPage list(@RequestParam(required = false) String q) {
        List<AgentDefinition> all = filterAgents(registryService.list(), q);
        return new AgentListPage(all, q).render();
    }

    /** The search field posts its form here; the response is the filtered list. */
    @PostMapping("/search")
    public UiPage search(@RequestBody Map<String, Object> raw) {
        return list(new FormBody(raw).str("q"));
    }

    /** Case-insensitive contains on name and description. */
    private static List<AgentDefinition> filterAgents(List<AgentDefinition> all, String q) {
        if (q == null || q.isBlank()) return all;
        String needle = q.toLowerCase();
        return all.stream()
                .filter(a -> (a.name() != null && a.name().toLowerCase().contains(needle))
                        || (a.description() != null && a.description().toLowerCase().contains(needle)))
                .toList();
    }

    @GetMapping("/new")
    public UiPage newForm() {
        return new AgentFormPage(null, llmConfigRepository, repository, objectMapper, skills()).render();
    }

    @GetMapping("/{id}")
    public ResponseEntity<UiPage> detail(@PathVariable("id") String idValue,
                                         @RequestParam(required = false) String section,
                                         @RequestParam(required = false) String row,
                                         @AuthenticationPrincipal OidcUser user) {
        AgentId id = AgentId.of(idValue);
        String userId = user.getPreferredUsername();
        return registryService.find(id)
                .map(a -> ResponseEntity.ok(new AgentDetailPage(a, userId, sessionRepository, section, row).render()))
                .orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}/sessions/{sessionId}")
    public ResponseEntity<UiPatch> deleteSession(@PathVariable("id") String idValue,
                                                 @PathVariable("sessionId") String sessionIdValue,
                                                 @AuthenticationPrincipal OidcUser user) {
        AgentId id = AgentId.of(idValue);
        String userId = user.getPreferredUsername();
        sessionRepository.deleteById(SessionId.of(sessionIdValue));
        return registryService.find(id)
                .map(a -> ResponseEntity.ok(new AgentDetailPage(a, userId, sessionRepository).refreshSessions()))
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/{id}/edit")
    public ResponseEntity<UiPage> editForm(@PathVariable("id") String idValue) {
        AgentId id = AgentId.of(idValue);
        return registryService.find(id)
                .map(a -> ResponseEntity.ok(new AgentFormPage(a, llmConfigRepository, repository, objectMapper, skills()).render()))
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<UiPage> create(@RequestBody Map<String, Object> raw,
                                         @AuthenticationPrincipal OidcUser user) {
        var body = new FormBody(raw);
        AgentSpec spec = new AgentSpec(
                body.str("name"), body.str("description"),
                body.str("systemPrompt"), body.str("welcomeMessage"),
                body.str("llmConfigName"));
        var agent = registryService.create(spec);
        // responseReviewers, maxIterations, toolSearch and memoryConfig are
        // applied as a follow-up patch (AgentSpec doesn't carry them yet).
        // Default maxIterations = whatever create() picked (10).
        AgentPatch patch = AgentPatch.of()
                .withGroup(body.str("group"))
                .withIcon(body.str("icon"))
                .withMaxIterations(body.num("maxIterations", agent.maxIterations()))
                .withResponseReviewers(body.strList("responseReviewers"))
                .withCallableAgents(body.strList("callableAgents"))
                .withToolSearch(toolSearchFromForm(body))
                .withSkills(skillsFromForm(body));
        try {
            patch = withMemoryConfig(patch, body);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        }
        val def = registryService.update(agent.id(), patch);
        return detail(def.id().value(), null, null, user);
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
        AgentDefinition existing = registryService.find(id).orElse(null);
        if (existing == null) {
            return ResponseEntity.notFound().build();
        }
        AgentPatch patch = AgentPatch.of()
                .withName(body.str("name"))
                .withDescription(body.str("description"))
                .withGroup(body.str("group"))
                .withIcon(body.str("icon"))
                .withSystemPrompt(body.str("systemPrompt"))
                .withWelcomeMessage(body.str("welcomeMessage"))
                .withLlmConfigName(body.str("llmConfigName"))
                .withMaxIterations(body.num("maxIterations", existing.maxIterations()))
                .withResponseReviewers(body.strList("responseReviewers"))
                .withCallableAgents(body.strList("callableAgents"))
                .withToolSearch(toolSearchFromForm(body))
                .withSkills(skillsFromForm(body));
        try {
            patch = withMemoryConfig(patch, body);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        }
        try {
            val def = registryService.update(id, patch, VersionedForms.version(body));
            return detail(def.id().value(), null, null, user);
        } catch (StaleVersionException e) {
            return ResponseEntity.ok(VersionedForms.changedMeanwhile("Agent '" + existing.name() + "'"));
        }
    }

    /**
     * The form's Memory (JSON) field: blank keeps the stored config, content
     * must parse into a {@link MemoryConfig}
     * (the {@code kind} tag picks the strategy, the record validates its
     * ranges).
     *
     * @throws IllegalArgumentException on unparseable JSON or invalid values
     */
    private AgentPatch withMemoryConfig(AgentPatch patch, FormBody body) {
        String json = body.str("memoryConfig");
        if (json == null || json.isBlank()) return patch;
        try {
            return patch.withMemoryConfig(objectMapper.readValue(json,
                    MemoryConfig.class));
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid memory config: " + e.getMessage(), e);
        }
    }

    /** The agent form's tool-search checkbox + comma-separated groups field. */
    private static AgentDefinition.ToolSearchConfig toolSearchFromForm(FormBody body) {
        boolean enabled = Boolean.TRUE.equals(body.bool("toolSearchEnabled"));
        String raw = body.str("toolSearchGroups");
        java.util.List<String> groups = raw == null || raw.isBlank()
                ? java.util.List.of()
                : java.util.Arrays.stream(raw.split(","))
                        .map(String::trim)
                        .filter(g -> !g.isEmpty())
                        .map(g -> g.toLowerCase(java.util.Locale.ROOT))
                        .toList();
        return new AgentDefinition.ToolSearchConfig(enabled, groups);
    }

    /**
     * The form's two skills fields. An empty selection with the switch on is
     * not "no skills" but "every skill there is" — see the field's hint.
     */
    private static AgentDefinition.SkillsConfig skillsFromForm(FormBody body) {
        boolean enabled = Boolean.TRUE.equals(body.bool("skillsEnabled"));
        java.util.List<String> names = body.strList("skills");
        return new AgentDefinition.SkillsConfig(enabled, names == null ? java.util.List.of() : names);
    }

    /** The skill catalog this host wires, or null when it wires none. */
    private ai.mindconnect.agent.runtime.skill.SkillCatalog skills() {
        return skillCatalog.getIfAvailable();
    }

    @PostMapping("/{id}/copy")
    public ResponseEntity<UiPage> copy(@PathVariable("id") String idValue) {
        AgentId id = AgentId.of(idValue);
        return registryService.find(id)
                .map(existing -> {
                    var copy = registryService.copy(id);
                    return ResponseEntity.ok(new AgentFormPage(copy, llmConfigRepository, repository, objectMapper, skills()).render());
                })
                .orElse(ResponseEntity.notFound().build());
    }

    // ── Tool endpoints ──────────────────────────────────────────────────────

    @GetMapping("/{id}/tools/new")
    public ResponseEntity<UiPage> newToolForm(@PathVariable("id") String idValue) {
        AgentId id = AgentId.of(idValue);
        return registryService.find(id)
                .map(a -> ResponseEntity.ok(new ToolFormPage(a, null, toolRegistry).render()))
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/{id}/tools/{toolId}")
    public ResponseEntity<UiPage> viewTool(@PathVariable("id") String idValue,
                                           @PathVariable("toolId") String toolIdValue) {
        AgentId id = AgentId.of(idValue);
        AgentToolId toolId = AgentToolId.of(toolIdValue);
        var agentOpt = registryService.find(id);
        if (agentOpt.isEmpty()) return ResponseEntity.notFound().build();
        var a    = agentOpt.get();
        var tool = a.tools().stream().filter(t -> t.id().equals(toolId)).findFirst().orElse(null);
        if (tool == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(new ToolDetailPage(a, tool, toolRegistry).render());
    }

    @GetMapping("/{id}/tools/{toolId}/edit")
    public ResponseEntity<UiPage> editToolForm(@PathVariable("id") String idValue,
                                               @PathVariable("toolId") String toolIdValue) {
        AgentId id = AgentId.of(idValue);
        AgentToolId toolId = AgentToolId.of(toolIdValue);
        var agentOpt = registryService.find(id);
        if (agentOpt.isEmpty()) return ResponseEntity.notFound().build();
        var a    = agentOpt.get();
        var tool = a.tools().stream().filter(t -> t.id().equals(toolId)).findFirst().orElse(null);
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
        var agentOpt = registryService.find(id);
        if (agentOpt.isEmpty()) return ResponseEntity.notFound().build();
        var a    = agentOpt.get();
        var tool = a.tools().stream().filter(t -> t.id().equals(toolId)).findFirst().orElse(null);
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
        var agentOpt = registryService.find(id);
        if (agentOpt.isEmpty()) return ResponseEntity.notFound().build();
        var a    = agentOpt.get();
        var tool = a.tools().stream().filter(t -> t.id().equals(toolId)).findFirst().orElse(null);
        if (tool == null) return ResponseEntity.notFound().build();
        var body = new FormBody(raw);
        String argsJson = body.str("arguments");
        ToolTestService.Result result = toolTestService.test(a, tool, argsJson);
        return ResponseEntity.ok(toolTestDialog(a, tool, argsJson, result));
    }

    /**
     * The tool-test dialog as a remove+append patch on the body-level dialog
     * host (same pattern as the workflow admin): remove is a no-op on first
     * open and replaces the modal in place on a re-render; a null close-href
     * just removes the overlay without navigating.
     */
    private UiPatch toolTestDialog(AgentDefinition agent, AgentTool tool,
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
        return registryService.find(id)
                .map(a -> {
                    AgentTool tool = toolFromBody(AgentToolId.random(), new FormBody(raw));
                    registryService.updateTools(id, tools -> {
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
        return registryService.find(id)
                .map(a -> {
                    AgentTool updated = toolFromBody(toolId, new FormBody(raw));
                    registryService.updateTools(id, tools -> tools.stream()
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
        return registryService.find(id)
                .map(a -> {
                    registryService.updateTools(id, tools -> tools.stream()
                            .filter(t -> !t.id().equals(toolId)).toList());
                    return registryService.find(id)
                            .map(updated -> ResponseEntity.ok(new AgentDetailPage(updated, userId, sessionRepository).refreshTools()))
                            .orElse(ResponseEntity.<UiPatch>notFound().build());
                })
                .orElse(ResponseEntity.notFound().build());
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
        boolean enabled = Boolean.TRUE.equals(body.bool("enabled"));
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
        return new AgentTool(toolId, name, body.str("description"), overrides, enabled, deferred,
                needsApproval, maxResultChars);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<UiPage> delete(@PathVariable("id") String idValue) {
        AgentId id = AgentId.of(idValue);
        return registryService.find(id)
                .map(existing -> {
                    registryService.delete(id);
                    List<AgentDefinition> all = registryService.list();
                    return ResponseEntity.ok(new AgentListPage(all).render());
                })
                .orElse(ResponseEntity.notFound().build());
    }
}
