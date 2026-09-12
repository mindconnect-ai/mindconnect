package ai.mindconnect.agent.runtime.skill;

import java.util.List;
import java.util.Optional;

/**
 * Outbound port for the skills this installation stores — the ones created
 * in the admin UI, as opposed to those a project or a user keeps in files
 * ({@link FileSkills}).
 *
 * <p>{@link #save} carries the same version check every other store here
 * does: a skill saves only against the version it was read with.
 */
public interface SkillRepository {

    /** Every stored skill, by name. */
    List<Skill> findAll();

    Optional<Skill> findById(SkillId id);

    /** Case-insensitive, like every other name lookup in the runtime. */
    Optional<Skill> findByName(String name);

    /** Stores the skill and returns it with its new version. */
    Skill save(Skill skill);

    void deleteById(SkillId id);

    /** A store with nothing in it, for a runtime assembled without one. */
    static SkillRepository empty() {
        return new SkillRepository() {
            @Override public List<Skill> findAll() { return List.of(); }
            @Override public Optional<Skill> findById(SkillId id) { return Optional.empty(); }
            @Override public Optional<Skill> findByName(String name) { return Optional.empty(); }
            @Override public Skill save(Skill skill) {
                throw new UnsupportedOperationException("This runtime stores no skills");
            }
            @Override public void deleteById(SkillId id) { }
        };
    }
}
