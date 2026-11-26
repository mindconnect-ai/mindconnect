package ai.mindconnect.agent.runtime.capabilities.skill;

import java.util.List;
import java.util.Optional;

import ai.mindconnect.agent.runtime.api.domain.Session;

/**
 * Where skills come from: a list in code, {@code .mc/skills} folders, a
 * store. Contributed; several stand side by side and the first that knows a
 * name wins. A skill a resolver returns for a session is active in it —
 * there is no separate switch.
 */
public interface SkillResolver {

    List<SkillSummary> skills(Session session);

    Optional<SkillDefinition> resolve(String skillName, Session session);
}
