package ai.mindconnect.agent.runtime.adapter.repo.memory;

import ai.mindconnect.agent.runtime.skill.Skill;
import ai.mindconnect.agent.runtime.skill.SkillId;
import ai.mindconnect.agent.runtime.skill.SkillRepository;
import ai.mindconnect.common.Versions;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Skills in memory — for tests and for an embedding that wants the feature
 * without a store on disk. Same version check as the file and Postgres
 * stores, so a test that relies on it behaves the same here.
 */
public class InMemorySkillRepository implements SkillRepository {

    private final Map<SkillId, Skill> skills = new ConcurrentHashMap<>();

    @Override
    public List<Skill> findAll() {
        return skills.values().stream().sorted(Comparator.comparing(Skill::name)).toList();
    }

    @Override
    public Optional<Skill> findById(SkillId id) {
        return Optional.ofNullable(skills.get(id));
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
        return skills.compute(valid.id(), (id, current) -> valid.withVersion(Versions.next(
                current == null ? null : current.version(), valid.version(), "Skill", id.value())));
    }

    @Override
    public void deleteById(SkillId id) {
        skills.remove(id);
    }
}
