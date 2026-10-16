package ai.mindconnect.agent.skill.adapter.memory;

import ai.mindconnect.agent.PerNamespace;
import ai.mindconnect.agent.ScopeSupplier;
import ai.mindconnect.agent.skill.Skill;
import ai.mindconnect.agent.skill.SkillId;
import ai.mindconnect.agent.skill.SkillRepository;
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

    private final PerNamespace<Map<SkillId, Skill>> skills;

    public InMemorySkillRepository(ScopeSupplier scope) {
        this.skills = new PerNamespace<>(scope, ConcurrentHashMap::new);
    }

    @Override
    public List<Skill> findAll() {
        return skills.current().values().stream().sorted(Comparator.comparing(Skill::name)).toList();
    }

    @Override
    public Optional<Skill> findById(SkillId id) {
        return Optional.ofNullable(skills.current().get(id));
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
        return skills.current().compute(valid.id(), (id, current) -> valid.withVersion(Versions.next(
                current == null ? null : current.version(), valid.version(), "Skill", id.value())));
    }

    @Override
    public void deleteById(SkillId id) {
        skills.current().remove(id);
    }
}
