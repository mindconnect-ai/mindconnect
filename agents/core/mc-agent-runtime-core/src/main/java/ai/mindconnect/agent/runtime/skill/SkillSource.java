package ai.mindconnect.agent.runtime.skill;

/**
 * Where a skill came from. What it decides is who may change it — a stored
 * skill is edited in the admin UI, a skill read off disk is edited in its
 * file — and which one wins when two carry the same name: the more specific
 * source does, {@link #MANAGED} being the least specific of the three.
 */
public enum SkillSource {

    /** Stored by this installation: created in the admin UI, in the namespace's store. */
    MANAGED,

    /** A {@code SKILL.md} under the user's own skills directory; theirs in every project. */
    USER,

    /** A {@code SKILL.md} under the session's working directory — the project's own. */
    PROJECT
}
