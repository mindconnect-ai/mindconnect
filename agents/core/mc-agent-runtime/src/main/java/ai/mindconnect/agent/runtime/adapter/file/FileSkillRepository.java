package ai.mindconnect.agent.runtime.adapter.file;

import ai.mindconnect.agent.Namespace;
import ai.mindconnect.agent.runtime.skill.Skill;
import ai.mindconnect.agent.runtime.skill.SkillId;
import ai.mindconnect.agent.runtime.skill.SkillRepository;
import ai.mindconnect.common.Versions;
import ai.mindconnect.filerepo.Documents;
import ai.mindconnect.filerepo.FileRepo;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.logging.Logger;

/**
 * Stores the installation's own skills under
 * {@code {base}/{namespace}/system/skills/{id}.json}.
 *
 * <p>One document per skill, like every other thing an operator creates
 * here. The {@code SKILL.md} files a project or a user keeps are a
 * different matter: those are read where they lie and never written
 * ({@code FileSkills}).
 *
 * <p>{@link #save} checks the version a skill carries against the stored one
 * and stores it one higher, both under the file's write lock — two edits of
 * the same skill cannot both pass the check.
 */
public class FileSkillRepository implements SkillRepository {

    private static final Logger log = Logger.getLogger(FileSkillRepository.class.getName());
    private static final String DIR = "system/skills";

    private final Documents<SkillId, Skill> skills;

    public FileSkillRepository(Path agentStorageDir, ObjectMapper objectMapper, Namespace namespace) {
        FileRepo repo = FileRepo.open(agentStorageDir, namespace.value());
        this.skills = Documents.of(Skill.class)
                .path((SkillId id) -> DIR + "/" + id.value() + ".json")
                .build(repo, objectMapper);
        log.info("SkillRepository storage: " + repo.resolve(DIR));
    }

    @Override
    public List<Skill> findAll() {
        return skills.findAll(DIR).stream()
                .sorted(Comparator.comparing(Skill::name))
                .toList();
    }

    @Override
    public Optional<Skill> findById(SkillId id) {
        // The directory is flat: a file of another tenant with the same value is not this skill.
        return skills.find(id).filter(skill -> skill.id().equals(id));
    }

    @Override
    public Optional<Skill> findByName(String name) {
        if (name == null) return Optional.empty();
        return findAll().stream()
                .filter(skill -> skill.name().equalsIgnoreCase(name.strip()))
                .findFirst();
    }

    @Override
    public Skill save(Skill skill) {
        Skill valid = skill.validated();
        return skills.compute(valid.id(), current -> valid.withVersion(Versions.next(
                current.map(Skill::version).orElse(null), valid.version(),
                "Skill", valid.id().value())));
    }

    @Override
    public void deleteById(SkillId id) {
        skills.delete(id);
    }
}
