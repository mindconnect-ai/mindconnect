package ai.mindconnect.agent.runtime.infrastructure;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import ai.mindconnect.agent.management.directory.WorkingDirPolicy;
import ai.mindconnect.agent.skill.domain.FileSkills;
import ai.mindconnect.agent.skill.domain.Skill;
import ai.mindconnect.agent.skill.domain.SkillSource;

/**
 * Where a user's own {@code SKILL.md} files live. A template, for the same
 * reason the instruction files' is: {@code ~/.mindconnect/skills} is right
 * on a desktop, and on a server it would be the service account's skills for
 * everybody — put {@code {user}} in it and each user gets their own.
 */
public final class UserSkillDirectory {

    /** The value that switches the users' own skills off. */
    public static final String OFF = "off";

    /** {@code null} when the users' own skills are switched off. */
    private final String template;

    private UserSkillDirectory(String template) {
        this.template = template;
    }

    /**
     * @param template a plain path, or one carrying {@code {user}}; blank is
     *                 {@code ~/.mindconnect/skills}, {@link #OFF} none
     */
    public static UserSkillDirectory of(String template) {
        if (template == null || template.isBlank()) {
            String home = System.getProperty("user.home");
            return new UserSkillDirectory(home == null || home.isBlank() ? null : home + "/.mindconnect/skills");
        }
        return new UserSkillDirectory(OFF.equalsIgnoreCase(template.strip()) ? null : template.strip());
    }

    /** The {@code SKILL.md} files in the user's directory, as they are. */
    public List<Skill> skills(String userId) {
        return directory(userId).map(dir -> FileSkills.list(dir, SkillSource.USER)).orElse(List.of());
    }

    private Optional<Path> directory(String userId) {
        if (template == null) {
            return Optional.empty();
        }
        try {
            if (!template.contains(WorkingDirPolicy.USER_PLACEHOLDER)) {
                return Optional.of(WorkingDirPolicy.expand(template));
            }
            if (userId == null || userId.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(WorkingDirPolicy.expand(
                    template.replace(WorkingDirPolicy.USER_PLACEHOLDER, WorkingDirPolicy.pathSafe(userId))));
        } catch (InvalidPathException e) {
            return Optional.empty();
        }
    }
}
