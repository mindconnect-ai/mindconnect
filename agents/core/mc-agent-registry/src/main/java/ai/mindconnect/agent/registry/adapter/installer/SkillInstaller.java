package ai.mindconnect.agent.registry.adapter.installer;

import ai.mindconnect.agent.registry.domain.ImportMode;
import ai.mindconnect.agent.registry.domain.ImportStatus;
import ai.mindconnect.agent.registry.domain.ImportedItem;
import ai.mindconnect.agent.registry.domain.RegistryEntry;
import ai.mindconnect.agent.registry.domain.RegistryItemType;
import ai.mindconnect.agent.registry.port.out.RegistryInstaller;
import ai.mindconnect.agent.runtime.skill.Skill;
import ai.mindconnect.agent.runtime.skill.SkillId;
import ai.mindconnect.agent.runtime.skill.SkillRepository;
import ai.mindconnect.agent.runtime.skill.SkillSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Optional;

/**
 * Installs a {@code skill} entry: one {@code SKILL.md} — front matter with
 * name, description and tools, then the instructions — stored as a managed
 * skill of this installation, the same as one written in the admin UI.
 *
 * <p>A new skill gets a fresh {@link SkillId}; a re-import keeps the local id
 * and version so that the admin UI's links to it keep working, and keeps the
 * enabled flag as the operator left it — a skill switched off here stays off
 * when a newer text arrives. The name comes from the front matter, falling
 * back to the entry's name when the file names none.
 *
 * <p>Only the file itself is installed. A skill in a repository may keep
 * scripts or templates beside its {@code SKILL.md}; they stay there, so a
 * registry skill has to carry what it needs in its own text.
 */
public class SkillInstaller implements RegistryInstaller {

    private static final Logger log = LoggerFactory.getLogger(SkillInstaller.class);

    private final SkillRepository repository;

    public SkillInstaller(SkillRepository repository) {
        this.repository = repository;
    }

    @Override
    public RegistryItemType type() {
        return RegistryItemType.SKILL;
    }

    @Override
    public boolean exists(String name) {
        return name != null && repository.findByName(name).isPresent();
    }

    @Override
    public ImportedItem remove(RegistryEntry entry) {
        Optional<Skill> existing = repository.findByName(entry.name());
        if (existing.isEmpty()) {
            return ImportedItem.skipped(entry, entry.name(), "no skill of this name is here");
        }
        repository.deleteById(existing.get().id());
        log.info("Removed skill '{}' (registry entry '{}')", entry.name(), entry.id());
        return ImportedItem.removed(entry, entry.name());
    }

    @Override
    public ImportedItem install(RegistryEntry entry, String content, ImportMode mode) throws Exception {
        Skill incoming = Skill.fromMarkdown(entry.name(), content, SkillSource.MANAGED, null);
        if (incoming == null) {
            throw new IllegalArgumentException("not a skill: the file has no instructions, or its name "
                    + "is not one a model could type (lower-case letters, digits and dashes)");
        }
        String name = incoming.name();
        Optional<Skill> existing = repository.findByName(name);

        if (existing.isPresent() && mode == ImportMode.SKIP_EXISTING) {
            return ImportedItem.skipped(entry, name, "a skill of this name is already here");
        }

        Instant now = Instant.now();
        Skill toSave = new Skill(
                existing.map(Skill::id).orElseGet(SkillId::random),
                name,
                incoming.description(),
                existing.map(Skill::group).orElse(incoming.group()),
                incoming.instructions(),
                incoming.tools(),
                existing.map(Skill::enabled).orElse(true),
                SkillSource.MANAGED,
                null,
                existing.map(Skill::createdAt).orElse(now),
                now,
                existing.map(Skill::version).orElse(null));
        repository.save(toSave);
        log.info("Imported skill '{}' from registry entry '{}'", name, entry.id());

        return new ImportedItem(entry.id(), type(), name,
                existing.isPresent() ? ImportStatus.UPDATED : ImportStatus.IMPORTED, null);
    }
}
