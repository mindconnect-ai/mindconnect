package ai.mindconnect.adminui.ui.controller;

import ai.mindconnect.adminui.ui.page.SkillDetailPage;
import ai.mindconnect.adminui.ui.page.SkillFormPage;
import ai.mindconnect.adminui.ui.page.SkillListPage;
import ai.mindconnect.agent.UserId;
import ai.mindconnect.agent.runtime.skill.Skill;
import ai.mindconnect.agent.runtime.skill.SkillCatalog;
import ai.mindconnect.agent.runtime.skill.SkillId;
import ai.mindconnect.agent.runtime.skill.SkillRepository;
import ai.mindconnect.chatui.ui.controller.FormBody;
import ai.mindconnect.common.StaleVersionException;
import ai.mindconnect.ui.model.UiPage;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The skills screens. Ids in paths are their plain values.
 *
 * <p>The list shows every stored skill — a switched-off one too, since this
 * is where it is switched back on — and the {@code SKILL.md} files in the
 * signed-in user's own skills directory. A
 * project's skills belong to a session's working directory and show up in
 * the chat that works there, not on this screen.
 *
 * <p>Only stored skills are writable here. A file is edited where it lies,
 * and this UI has no business writing into somebody's repository or home
 * directory — so a file-borne skill has a detail view and no buttons.
 */
@RestController
@RequestMapping("/admin/api/skills")
public class SkillUiController {

    private final ObjectProvider<SkillRepository> repositoryProvider;
    private final ObjectProvider<SkillCatalog> catalogProvider;

    /**
     * Both dependencies are optional, as the MCP gateway's are on the tools
     * screen: a host that wires no skill store still gets the screen, reading
     * whatever files there are and saying plainly that it cannot store one.
     */
    public SkillUiController(ObjectProvider<SkillRepository> repository,
                             ObjectProvider<SkillCatalog> catalog) {
        this.repositoryProvider = repository;
        this.catalogProvider = catalog;
    }

    /** The store, or one that holds nothing when this host wires none. */
    private SkillRepository repository() {
        SkillRepository stored = repositoryProvider.getIfAvailable();
        return stored == null ? SkillRepository.empty() : stored;
    }

    /** The catalog, or one with only the file scopes. */
    private SkillCatalog catalog() {
        SkillCatalog wired = catalogProvider.getIfAvailable();
        return wired == null ? SkillCatalog.none() : wired;
    }

    /** Whether this installation can store a skill at all. */
    private boolean canStore() {
        return repositoryProvider.getIfAvailable() != null;
    }

    @GetMapping
    public UiPage list(@AuthenticationPrincipal OidcUser user) {
        return new SkillListPage(visible(user)).render();
    }

    /** The search field posts its form here; the response is the filtered list. */
    @PostMapping("/search")
    public UiPage search(@RequestBody Map<String, Object> raw,
                         @AuthenticationPrincipal OidcUser user) {
        String q = new FormBody(raw).str("q");
        return new SkillListPage(filter(visible(user), q), q).render();
    }

    @GetMapping("/new")
    public UiPage newForm() {
        return new SkillFormPage(null).render();
    }

    @GetMapping("/{id}")
    public ResponseEntity<UiPage> detail(@PathVariable("id") String idValue,
                                         @AuthenticationPrincipal OidcUser user) {
        return find(idValue, user)
                .map(skill -> ResponseEntity.ok(new SkillDetailPage(skill).render()))
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/{id}/edit")
    public ResponseEntity<UiPage> editForm(@PathVariable("id") String idValue) {
        return repository().findById(SkillId.of(idValue))
                .map(skill -> ResponseEntity.ok(new SkillFormPage(skill).render()))
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * The skill as a {@code SKILL.md} — the form it moves in: put it under a
     * project's {@code .mindconnect/skills/} and it travels with the code.
     */
    @GetMapping(value = "/{id}/markdown", produces = "text/markdown")
    public ResponseEntity<String> markdown(@PathVariable("id") String idValue,
                                           @AuthenticationPrincipal OidcUser user) {
        return find(idValue, user)
                .map(skill -> ResponseEntity.ok(skill.toMarkdown()))
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody Map<String, Object> raw,
                                    @AuthenticationPrincipal OidcUser user) {
        if (!canStore()) return ResponseEntity.ok(VersionedForms.noStore());
        var body = new FormBody(raw);
        Skill skill = Skill.create(body.str("name"), body.str("description"),
                body.str("instructions"), toolList(body.str("tools")));
        if (!body.bool("enabled", true)) {
            skill = skill.withFields(skill.name(), skill.description(), skill.instructions(),
                    skill.tools(), false);
        }
        if (!skill.hasValidName()) {
            return ResponseEntity.ok(VersionedForms.unusableName("Skill", skill.name()));
        }
        if (nameTaken(skill)) {
            return ResponseEntity.ok(VersionedForms.nameTaken("Skill", skill.name()));
        }
        Skill saved = repository().save(skill);
        return detail(saved.id().value(), user);
    }

    /**
     * Saves the edit form against the version it was opened with; when the
     * skill was saved since, the form stays on screen with a toast instead.
     */
    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable("id") String idValue,
                                    @RequestBody Map<String, Object> raw,
                                    @AuthenticationPrincipal OidcUser user) {
        if (!canStore()) return ResponseEntity.ok(VersionedForms.noStore());
        var body = new FormBody(raw);
        Skill existing = repository().findById(SkillId.of(idValue)).orElse(null);
        if (existing == null) return ResponseEntity.notFound().build();
        Skill updated = existing
                .withFields(body.str("name"), body.str("description"), body.str("instructions"),
                        toolList(body.str("tools")), body.bool("enabled", existing.enabled()))
                .withVersion(VersionedForms.version(body));
        if (!updated.hasValidName()) {
            return ResponseEntity.ok(VersionedForms.unusableName("Skill", updated.name()));
        }
        if (nameTaken(updated)) {
            return ResponseEntity.ok(VersionedForms.nameTaken("Skill", updated.name()));
        }
        try {
            repository().save(updated);
        } catch (StaleVersionException e) {
            return ResponseEntity.ok(VersionedForms.changedMeanwhile("Skill '" + existing.name() + "'"));
        }
        return detail(idValue, user);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<UiPage> delete(@PathVariable("id") String idValue,
                                         @AuthenticationPrincipal OidcUser user) {
        SkillId id = SkillId.of(idValue);
        if (repository().findById(id).isEmpty()) return ResponseEntity.notFound().build();
        repository().deleteById(id);
        return ResponseEntity.ok(new SkillListPage(visible(user)).render());
    }

    /** Stored and looked up by id, or one of the files, which are keyed by name. */
    private Optional<Skill> find(String idValue, OidcUser user) {
        return repository().findById(SkillId.of(idValue))
                .or(() -> visible(user).stream()
                        .filter(skill -> skill.id().value().equals(idValue))
                        .findFirst());
    }

    /**
     * What this user can see: every stored skill — switched off or hidden
     * behind a file of the same name included, since this is where it is
     * switched back on or deleted — then their own files.
     */
    private List<Skill> visible(OidcUser user) {
        List<Skill> skills = new java.util.ArrayList<>(repository().findAll());
        skills.addAll(catalog().userSkills(userIdOf(user)));
        return List.copyOf(skills);
    }

    /** Whether another stored skill already carries {@code skill}'s name. */
    private boolean nameTaken(Skill skill) {
        return repository().findByName(skill.name())
                .filter(other -> !other.id().equals(skill.id()))
                .isPresent();
    }

    /** The signed-in user as an id, or {@code null} when there is none to read. */
    private static UserId userIdOf(OidcUser user) {
        String name = user == null ? null : user.getPreferredUsername();
        return name == null || name.isBlank() ? null : UserId.of(name);
    }

    /** The comma-separated tools field as a list; empty when it says nothing. */
    private static List<String> toolList(String value) {
        if (value == null || value.isBlank()) return List.of();
        return Arrays.stream(value.split(","))
                .map(String::strip)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    /** Case-insensitive contains on name and description. */
    private static List<Skill> filter(List<Skill> all, String q) {
        if (q == null || q.isBlank()) return all;
        String needle = q.toLowerCase();
        return all.stream()
                .filter(skill -> skill.name().contains(needle)
                        || skill.description().toLowerCase().contains(needle))
                .toList();
    }
}
