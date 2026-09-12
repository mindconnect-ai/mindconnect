package ai.mindconnect.agentrest.controller;

import ai.mindconnect.agent.runtime.skill.Skill;
import ai.mindconnect.agent.runtime.skill.SkillId;
import ai.mindconnect.agent.runtime.skill.SkillRepository;
import ai.mindconnect.common.StaleVersionException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * External REST API for the skills this installation stores — the ones an
 * agent with skills switched on can load with the {@code skill} tool.
 *
 * <p>Skills a project or a user keeps as {@code SKILL.md} files are read
 * where they lie and are not writable here; they show up in an agent's
 * catalog all the same.
 */
@Tag(name = "Skills", description = "Instruction packs an agent loads on demand: only name and "
        + "description are in its prompt, the body arrives when it calls the skill tool.")
@RestController
@RequestMapping("/api/skills")
public class SkillApiController {

    private static final Logger log = LoggerFactory.getLogger(SkillApiController.class);

    private final SkillRepository repository;

    public SkillApiController(SkillRepository repository) {
        this.repository = repository;
    }

    /**
     * A skill as the API takes it. {@code version} is the skill's version as
     * read: the save is refused with 409 when it was saved since; without
     * one it is applied to the skill as stored.
     */
    public record SkillRequest(String name, String description, String instructions,
                               List<String> tools, Boolean enabled, Long version) {}

    @Operation(summary = "List stored skills")
    @GetMapping
    public List<Skill> findAll() {
        return repository.findAll();
    }

    @Operation(summary = "Get a skill")
    @GetMapping("/{id}")
    public ResponseEntity<Skill> findById(@PathVariable String id) {
        return repository.findById(SkillId.of(id))
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @Operation(summary = "Get a skill as SKILL.md",
            description = "The skill in the portable form: front matter with name, description "
                    + "and tools, then the instructions. Drop it into a project's "
                    + ".mindconnect/skills/ to carry it with the code.")
    @GetMapping(value = "/{id}/markdown", produces = MediaType.TEXT_MARKDOWN_VALUE)
    public ResponseEntity<String> markdown(@PathVariable String id) {
        return repository.findById(SkillId.of(id))
                .map(skill -> ResponseEntity.ok(skill.toMarkdown()))
                .orElse(ResponseEntity.notFound().build());
    }

    @Operation(summary = "Create a skill",
            description = "Refused with 400 when the name is not lower-case letters, digits and "
                    + "dashes, and with 409 when a stored skill already has it.")
    @PostMapping
    public ResponseEntity<?> create(@RequestBody SkillRequest req) {
        log.info("POST /api/skills name={}", req.name());
        Skill skill = Skill.create(req.name(), req.description(), req.instructions(), req.tools());
        if (req.enabled() != null && !req.enabled()) {
            skill = skill.withFields(skill.name(), skill.description(), skill.instructions(),
                    skill.tools(), false);
        }
        ResponseEntity<String> refused = refusal(skill);
        return refused != null ? refused : ResponseEntity.ok(repository.save(skill));
    }

    /**
     * Why {@code skill} cannot be saved as it is, or {@code null} when it can:
     * a name no model could type is the caller's mistake (400), and a name
     * another stored skill has is a conflict (409) — the runtime looks skills
     * up by name, so the second one would never be reached.
     */
    private ResponseEntity<String> refusal(Skill skill) {
        if (!skill.hasValidName()) {
            return ResponseEntity.badRequest().body("Skill name '" + skill.name() + "' is not usable: use "
                    + "lower-case letters, digits and dashes, starting with a letter or digit, at most "
                    + "64 characters");
        }
        boolean taken = repository.findByName(skill.name())
                .filter(other -> !other.id().equals(skill.id()))
                .isPresent();
        return taken
                ? ResponseEntity.status(HttpStatus.CONFLICT).body("A skill named '" + skill.name() + "' already exists")
                : null;
    }

    @Operation(summary = "Update a skill",
            description = "Absent fields keep their current value. Send the skill's `version` as "
                    + "you read it to have the update refused with 409 when it was saved since.")
    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable String id, @RequestBody SkillRequest req) {
        log.info("PUT /api/skills/{}", id);
        Skill existing = repository.findById(SkillId.of(id)).orElse(null);
        if (existing == null) return ResponseEntity.notFound().build();
        Skill updated = existing
                .withFields(req.name() == null ? existing.name() : req.name(),
                        req.description() == null ? existing.description() : req.description(),
                        req.instructions() == null ? existing.instructions() : req.instructions(),
                        req.tools() == null ? existing.tools() : req.tools(),
                        req.enabled() == null ? existing.enabled() : req.enabled())
                .withVersion(req.version());
        ResponseEntity<String> refused = refusal(updated);
        if (refused != null) return refused;
        try {
            return ResponseEntity.ok(repository.save(updated));
        } catch (StaleVersionException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(e.getMessage());
        }
    }

    @Operation(summary = "Delete a skill")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        log.info("DELETE /api/skills/{}", id);
        SkillId skillId = SkillId.of(id);
        if (repository.findById(skillId).isEmpty()) return ResponseEntity.notFound().build();
        repository.deleteById(skillId);
        return ResponseEntity.noContent().build();
    }
}
