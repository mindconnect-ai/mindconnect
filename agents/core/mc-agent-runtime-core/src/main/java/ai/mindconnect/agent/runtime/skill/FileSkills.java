package ai.mindconnect.agent.runtime.skill;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * Skills kept in files rather than in the store: a project's own, in
 * {@code .mindconnect/skills/} under the session's working directory, and a
 * user's own, in the directory the installation configures for them.
 *
 * <p>Two layouts, both read:
 *
 * <pre>
 * .mindconnect/skills/weekly-report/SKILL.md   ← a directory, and the files it needs beside it
 * .mindconnect/skills/release.md               ← a single file, for a skill that needs nothing else
 * </pre>
 *
 * <p>The directory form is the one to reach for when the instructions point
 * at something — a template to fill in, a script to run, a checklist to
 * read. The skill is handed to the model with its directory named, so those
 * files are one {@code file_read} away.
 *
 * <p>Nothing here fails a turn: a file that cannot be read, or whose name no
 * model could type, is logged and skipped. A skill that does not load is a
 * skill the agent does not have, which is the state it was in before the
 * file existed.
 */
public final class FileSkills {

    private static final Logger log = LoggerFactory.getLogger(FileSkills.class);

    /** Where a project keeps its skills, relative to the working directory. */
    public static final String PROJECT_DIR = ".mindconnect/skills";

    /** The file a skill directory is known by. */
    public static final String SKILL_FILE = "SKILL.md";

    private static final String SUFFIX = ".md";

    /** Past this the file is a document, not a skill, and is not read at all. */
    static final int MAX_CHARS = 200_000;

    private FileSkills() {}

    /** The skills of the project the session works in; empty when it works nowhere in particular. */
    public static List<Skill> project(String workingDir) {
        return list(projectDir(workingDir), SkillSource.PROJECT);
    }

    /** {@code <workingDir>/.mindconnect/skills}, or {@code null} without a working directory. */
    public static Path projectDir(String workingDir) {
        if (workingDir == null || workingDir.isBlank()) return null;
        try {
            return Path.of(workingDir).resolve(PROJECT_DIR);
        } catch (InvalidPathException e) {
            return null;
        }
    }

    /**
     * Every skill in {@code dir}, in name order; empty when the directory is
     * absent, unreadable or holds none.
     */
    public static List<Skill> list(Path dir, SkillSource source) {
        if (dir == null || !Files.isDirectory(dir)) return List.of();
        List<Skill> skills = new ArrayList<>();
        try (Stream<Path> entries = Files.list(dir)) {
            entries.sorted().forEach(entry -> {
                Skill skill = read(entry, source);
                if (skill != null) skills.add(skill);
            });
        } catch (IOException | RuntimeException e) {
            log.debug("Skills in {} could not be listed: {}", dir, e.toString());
            return List.of();
        }
        return List.copyOf(skills);
    }

    /**
     * One entry of a skills directory: a directory holding a
     * {@link #SKILL_FILE}, or a {@code .md} file on its own. {@code null}
     * when it is neither, or does not parse.
     */
    private static Skill read(Path entry, SkillSource source) {
        if (Files.isDirectory(entry)) {
            Path file = entry.resolve(SKILL_FILE);
            return Files.isRegularFile(file)
                    ? parse(file, entry.getFileName().toString(), entry, source)
                    : null;
        }
        String fileName = entry.getFileName().toString();
        if (!fileName.toLowerCase(Locale.ROOT).endsWith(SUFFIX)) return null;
        return parse(entry, fileName.substring(0, fileName.length() - SUFFIX.length()),
                entry.getParent(), source);
    }

    private static Skill parse(Path file, String fallbackName, Path directory, SkillSource source) {
        try {
            if (Files.size(file) > MAX_CHARS) {
                log.info("Skill {} is too large to read ({} characters at most)", file, MAX_CHARS);
                return null;
            }
            Skill skill = Skill.fromMarkdown(fallbackName, Files.readString(file), source,
                    directory == null ? null : directory.toAbsolutePath().toString());
            if (skill == null) {
                log.info("Skill {} was skipped: it has no instructions, or a name that is not "
                        + "lower-case letters, digits and dashes", file);
            }
            return skill;
        } catch (IOException | RuntimeException e) {
            log.info("Skill {} could not be read: {}", file, e.toString());
            return null;
        }
    }
}
