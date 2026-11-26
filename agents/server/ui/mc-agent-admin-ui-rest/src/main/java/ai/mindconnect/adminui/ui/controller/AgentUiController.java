package ai.mindconnect.adminui.ui.controller;

import ai.mindconnect.adminui.ui.component.AgentFormComponent;
import ai.mindconnect.adminui.ui.page.AgentFormPage;
import ai.mindconnect.adminui.ui.page.AgentListPage;
import ai.mindconnect.agent.AgentId;
import ai.mindconnect.agent.SessionId;
import ai.mindconnect.agent.management.api.AgentManagement;
import ai.mindconnect.agent.management.api.ManagedAgent;
import ai.mindconnect.agent.management.api.SessionManagement;
import ai.mindconnect.agent.runtime.api.domain.AgentDefinition;
import ai.mindconnect.agent.runtime.api.domain.ModelSettings;
import ai.mindconnect.agent.sdk.AgentRuntime;
import ai.mindconnect.agent.skill.port.out.SkillRepository;
import ai.mindconnect.chatui.service.SessionOwnership;
import ai.mindconnect.chatui.ui.controller.FormBody;
import ai.mindconnect.common.DomainException;
import ai.mindconnect.llm.management.api.port.out.LlmConfigRepository;
import ai.mindconnect.ui.model.UiPage;
import ai.mindconnect.ui.model.UiPatch;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.*;

import java.util.ConcurrentModificationException;
import java.util.List;
import java.util.Map;

/**
 * The agent screens. Ids in paths are their plain values.
 */
@RestController
@RequestMapping("/admin/api/agents")
public class AgentUiController {

    /** The runtime's: the pages keep no way of their own to the agents. */
    private final AgentManagement agents;
    /** The runtime's too: an agent's sessions are listed and deleted where every session is. */
    private final SessionManagement sessions;
    private final LlmConfigRepository llmConfigRepository;
    private final ObjectMapper objectMapper;
    /** Fills the form's skills picker; optional, like the skills screen's own store. */
    private final ObjectProvider<SkillRepository> skillCatalog;
    private final AgentForm form;
    private final AgentDetailView detail;

    public AgentUiController(AgentRuntime runtime,
                             LlmConfigRepository llmConfigRepository,
                             ObjectMapper objectMapper,
                             ObjectProvider<SkillRepository> skillCatalog,
                             AgentDetailView detail) {
        this.agents = runtime.service(AgentManagement.class);
        this.sessions = runtime.service(SessionManagement.class);
        this.llmConfigRepository = llmConfigRepository;
        this.objectMapper = objectMapper;
        this.skillCatalog = skillCatalog;
        this.form = new AgentForm(objectMapper);
        this.detail = detail;
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
        return formPage(null).render();
    }

    /** The form of an agent, or of a new one for {@code null}, with every agent there is to pick from. */
    private AgentFormPage formPage(ManagedAgent agent) {
        return new AgentFormPage(agent, llmConfigRepository, agents.list(), objectMapper, skills());
    }

    @GetMapping("/{id}")
    public ResponseEntity<UiPage> detail(@PathVariable("id") String idValue,
                                         @RequestParam(required = false) String section,
                                         @RequestParam(required = false) String row,
                                         @AuthenticationPrincipal OidcUser user) {
        return detail.page(idValue, section, row, user);
    }

    @DeleteMapping("/{id}/sessions/{sessionId}")
    public ResponseEntity<UiPatch> deleteSession(@PathVariable("id") String idValue,
                                                 @PathVariable("sessionId") String sessionIdValue,
                                                 @AuthenticationPrincipal OidcUser user) {
        var sessionId = SessionId.of(sessionIdValue);
        // The tab lists the caller's sessions only; someone else's is answered like a missing one.
        if (sessions.find(sessionId).filter(session -> SessionOwnership.owns(session, user)).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        // Through the management: what else is kept of the session goes with it.
        sessions.delete(sessionId);
        return agents.find(AgentId.of(idValue))
                .map(a -> ResponseEntity.ok(detail.of(a, user).refreshSessions()))
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/{id}/edit")
    public ResponseEntity<UiPage> editForm(@PathVariable("id") String idValue) {
        return agents.find(AgentId.of(idValue))
                .map(a -> ResponseEntity.ok(formPage(a).render()))
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
            created = agents.create(form.agent(ManagedAgent.of(new AgentDefinition(name, "", "",
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
            changed = form.agent(existing, body);
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
        var picker = new AgentFormComponent(agent, llmConfigRepository, agents.list(), objectMapper, skills());
        return UiPatch.of().patch(UiPatch.Operation.replace("skills",
                picker.skillsField(AgentFormComponent.skillsMode(body.str(AgentFormComponent.SKILLS_MODE)),
                        body.strList("skills"))));
    }

    /** The skill store this host wires, or null when it wires none. */
    private SkillRepository skills() {
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
        return ResponseEntity.ok(formPage(agents.update(copy.id(), copy.version(), java.util.function.UnaryOperator.identity()))
                .render());
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
