package ai.mindconnect.agent.registry.adapter.installer;

import ai.mindconnect.agent.registry.domain.ImportMode;
import ai.mindconnect.agent.registry.domain.ImportStatus;
import ai.mindconnect.agent.registry.domain.ImportedItem;
import ai.mindconnect.agent.registry.domain.RegistryEntry;
import ai.mindconnect.agent.registry.domain.RegistryItemType;
import ai.mindconnect.agent.registry.port.out.RegistryInstaller;
import ai.mindconnect.agent.runtime.markdown.FrontMatter;
import ai.mindconnect.agent.runtime.skill.Skill;
import ai.mindconnect.agent.runtime.skill.SkillId;
import ai.mindconnect.agent.runtime.skill.SkillRepository;
import ai.mindconnect.agent.runtime.skill.SkillSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Installs a {@code skill} entry: one {@code SKILL.md} — front matter with
 * name, description and tools, then the instructions — stored as a managed
 * skill of this installation, the same as one written in the admin UI.
 *
 * <p>A new skill gets a fresh {@link SkillId}; a re-import keeps the local id
 * and version so that the admin UI's links to it keep working, and keeps the
 * enabled flag as the operator left it — a skill switched off here stays off
 * when a newer text arrives.
 *
 * <p>The skill is stored under the entry's name, lower-cased as a skill name
 * is, whatever the file's front matter calls it: {@link #exists} and
 * {@link #remove} only know the entry, and a skill stored under another name
 * would show as not installed and could not be removed again. A front matter
 * that names it differently is mentioned in the import line.
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
        String installed = installedName(name);
        return installed != null && repository.findByName(installed).isPresent();
    }

    @Override
    public ImportedItem remove(RegistryEntry entry) {
        String name = installedName(entry.name());
        Optional<Skill> existing = name == null ? Optional.empty() : repository.findByName(name);
        if (existing.isEmpty()) {
            return ImportedItem.skipped(entry, entry.name(), "no skill of this name is here");
        }
        repository.deleteById(existing.get().id());
        log.info("Removed skill '{}' (registry entry '{}')", name, entry.id());
        return ImportedItem.removed(entry, name);
    }

    @Override
    public ImportedItem install(RegistryEntry entry, String content, ImportMode mode) throws Exception {
        String name = installedName(entry.name());
        if (name == null || !Skill.NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("not a skill name: the registry names this skill '" + entry.name()
                    + "', which is not one a model could type (lower-case letters, digits and dashes)");
        }
        FrontMatter.Parsed parsed = FrontMatter.parse(content);
        String instructions = parsed.body().strip();
        if (instructions.isEmpty()) {
            throw new IllegalArgumentException("not a skill: the file has no instructions");
        }
        String ownName = parsed.get("name", null);
        String detail = ownName != null && !ownName.isBlank() && !name.equals(installedName(ownName))
                ? "stored as '" + name + "', the registry's name for it; the file calls itself '"
                        + ownName.strip() + "'"
                : null;
        List<String> tools = parsed.list("tools");
        Skill incoming = new Skill(SkillId.of(name), name, parsed.get("description", ""),
                parsed.get("group", null), instructions, tools == null ? List.of() : tools, true,
                SkillSource.MANAGED, null, null, null, null);
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
                existing.isPresent() ? ImportStatus.UPDATED : ImportStatus.IMPORTED, detail);
    }

    /**
     * The name a skill of this registry name is stored under — in {@link #install},
     * {@link #exists} and {@link #remove} alike. Lower-cased and stripped, as a skill's
     * own name is; {@code null} for none.
     */
    static String installedName(String registryName) {
        if (registryName == null || registryName.isBlank()) return null;
        return registryName.strip().toLowerCase(Locale.ROOT);
    }
}
