package ai.mindconnect.agent.runtime.skill;

import ai.mindconnect.agent.UserId;
import ai.mindconnect.agent.runtime.domain.AgentDefinition;
import ai.mindconnect.agent.runtime.domain.AgentSession;
import ai.mindconnect.agent.runtime.service.WorkingDirPolicy;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * What an agent may load at run time, from the three places skills come
 * from, and what the system prompt says about it.
 *
 * <ul>
 *   <li><b>Managed</b> — stored by this installation, created in the admin
 *       UI. Applies wherever the agent runs.</li>
 *   <li><b>User</b> — {@code SKILL.md} files under the user's own skills
 *       directory ({@code mindconnect.agent.skills.user-dir}). Theirs in
 *       every project.</li>
 *   <li><b>Project</b> — {@code .mindconnect/skills/} in the session's
 *       working directory. The project's own way of doing things, kept
 *       beside the code it is about.</li>
 * </ul>
 *
 * <p>Read fresh every round, so an edited skill is in effect on the next
 * turn without a restart — the same rule the instruction files follow.
 *
 * <p>Two skills of the same name are one skill: the more specific source
 * wins, project over user over managed. A project that disagrees with the
 * installation about how its reports are written is right about its own
 * reports, which is the whole reason its file is there.
 *
 * <p>The user directory is a template, for the same reason the instruction
 * files' is: {@code ~/.mindconnect/skills} is right on a desktop, and on a
 * server it would be the service account's skills for everybody — put
 * {@code {user}} in it and each user gets their own. {@code off} switches
 * the user scope off.
 */
public final class SkillCatalog {

    /** The value that switches the user scope off. */
    public static final String OFF = "off";

    private final SkillRepository stored;
    /** {@code null} when the user scope is off. */
    private final String userDirTemplate;

    private SkillCatalog(SkillRepository stored, String userDirTemplate) {
        this.stored = stored == null ? SkillRepository.empty() : stored;
        this.userDirTemplate = userDirTemplate;
    }

    /**
     * With the given store and user directory: a plain path, or one carrying
     * {@code {user}}. Blank falls back to {@code ~/.mindconnect/skills};
     * {@link #OFF} drops the user scope.
     */
    public static SkillCatalog of(SkillRepository stored, String userDirTemplate) {
        if (userDirTemplate == null || userDirTemplate.isBlank()) {
            return new SkillCatalog(stored, defaultUserDir());
        }
        String template = userDirTemplate.strip();
        return new SkillCatalog(stored, OFF.equalsIgnoreCase(template) ? null : template);
    }

    /** Stored and project skills only. */
    public static SkillCatalog of(SkillRepository stored) {
        return new SkillCatalog(stored, null);
    }

    /**
     * No stored and no user skills — what a runtime assembled without a
     * skill store has. A project's own skills are still read: they belong to
     * the working directory the session was pointed at, like its
     * {@code AGENTS.md}.
     */
    public static SkillCatalog none() {
        return new SkillCatalog(SkillRepository.empty(), null);
    }

    /** {@code ~/.mindconnect/skills}, or {@code null} for an account without a home. */
    static String defaultUserDir() {
        String home = System.getProperty("user.home");
        return home == null || home.isBlank() ? null : home + "/.mindconnect/skills";
    }

    /**
     * Every skill this user and this working directory can see, in name
     * order — managed, then the user's, then the project's, each overriding
     * the one before by name. Skills switched off are left out.
     */
    public List<Skill> all(UserId userId, String workingDir) {
        Map<String, Skill> byName = new LinkedHashMap<>();
        for (Skill skill : stored.findAll()) {
            if (skill.enabled()) byName.put(skill.name(), skill);
        }
        for (Skill skill : FileSkills.list(userDir(userId), SkillSource.USER)) {
            byName.put(skill.name(), skill);
        }
        for (Skill skill : FileSkills.project(workingDir)) {
            byName.put(skill.name(), skill);
        }
        List<Skill> skills = new ArrayList<>(byName.values());
        skills.sort(java.util.Comparator.comparing(Skill::name));
        return List.copyOf(skills);
    }

    /**
     * The {@code SKILL.md} files in this user's own skills directory, as they
     * are — not merged with the stored skills. What a screen that manages
     * skills lists beside the store: {@link #all} answers what an agent can
     * load, and leaves out exactly the skills such a screen must still show,
     * a stored one switched off or hidden behind a file of the same name.
     */
    public List<Skill> userSkills(UserId userId) {
        return FileSkills.list(userDir(userId), SkillSource.USER);
    }

    /**
     * The skills a binding may load: everything {@link #all} found when it
     * names none, and only the ones it names otherwise. This is the form the
     * {@code skill} tool works in — it knows the names its binding carries,
     * not the agent definition they came from.
     */
    public List<Skill> availableTo(List<String> names, UserId userId, String workingDir) {
        List<Skill> all = all(userId, workingDir);
        if (names == null || names.isEmpty()) return all;
        return all.stream().filter(skill -> named(names, skill.name())).toList();
    }

    /** The skill of that name among {@code names}, if there is one. */
    public Optional<Skill> find(List<String> names, UserId userId, String workingDir, String name) {
        if (name == null || name.isBlank()) return Optional.empty();
        String wanted = name.strip().toLowerCase(Locale.ROOT);
        return availableTo(names, userId, workingDir).stream()
                .filter(skill -> skill.name().equals(wanted))
                .findFirst();
    }

    /**
     * The skills this agent may load in this session. Empty when the agent
     * has skills switched off — then it has no {@code skill} tool either,
     * and the prompt says nothing about them.
     */
    public List<Skill> available(AgentDefinition def, AgentSession session) {
        if (def == null) return List.of();
        AgentDefinition.SkillsConfig config = def.skillsOrOff();
        if (!config.enabled()) return List.of();
        return availableTo(config.names(), userIdOf(session), workingDirOf(session));
    }

    /** The skill of that name this agent may load, if there is one. */
    public Optional<Skill> find(AgentDefinition def, AgentSession session, String name) {
        if (def == null || !def.skillsOrOff().enabled()) return Optional.empty();
        return find(def.skillsOrOff().names(), userIdOf(session), workingDirOf(session), name);
    }

    /**
     * What the system prompt says about the agent's skills: one line each,
     * name and description, and how to get the rest. The instructions
     * themselves stay out — a skill that costs its full length from the
     * first token on is a prompt, and could have been written in the prompt.
     */
    public String promptSection(AgentDefinition def, AgentSession session) {
        List<Skill> skills = available(def, session);
        if (skills.isEmpty()) return "";
        StringBuilder out = new StringBuilder("\n\n## Skills\n"
                + "Skills are instructions written down for work that is done a particular way here. "
                + "Only their names and descriptions are listed below; call the `skill` tool with a "
                + "name to read the full instructions BEFORE starting the work it covers, and then "
                + "follow them over your own habits. Reach for one whenever its description matches "
                + "the task — including when you think you already know how.\n");
        for (Skill skill : skills) {
            out.append(skill.promptLine()).append('\n');
        }
        return out.toString();
    }

    private static UserId userIdOf(AgentSession session) {
        return session == null ? null : session.userId();
    }

    private static String workingDirOf(AgentSession session) {
        return session == null ? null : session.workingDir();
    }

    /**
     * The directory this user's own skills live in, or {@code null} when the
     * scope is off or the id cannot fill the placeholder.
     */
    Path userDir(UserId user) {
        if (userDirTemplate == null) return null;
        try {
            if (!userDirTemplate.contains(WorkingDirPolicy.USER_PLACEHOLDER)) {
                return WorkingDirPolicy.expand(userDirTemplate);
            }
            String userId = user == null ? null : user.value();
            if (userId == null || userId.isBlank()) return null;
            return WorkingDirPolicy.expand(userDirTemplate.replace(
                    WorkingDirPolicy.USER_PLACEHOLDER, WorkingDirPolicy.pathSafe(userId)));
        } catch (InvalidPathException e) {
            return null;
        }
    }

    private static boolean named(List<String> names, String name) {
        return names.stream().anyMatch(n -> n != null && n.strip().equalsIgnoreCase(name));
    }
}
