package ai.mindconnect.agent.management.directory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;

/**
 * Standing instructions an agent should work by, written once in a file
 * instead of in every message: how a project is built and tested, what its
 * conventions are, how the user likes answers. Two scopes, both read fresh
 * every round and put into the system prompt.
 *
 * <ul>
 *   <li><b>User</b> — in the directory this object is configured with.
 *       Applies in every project.</li>
 *   <li><b>Project</b> — in the session's working directory. Applies while
 *       working there, and comes after the user's in the prompt, so the
 *       more specific one has the last word.</li>
 * </ul>
 *
 * <p>The user directory is a template, because one machine and one server
 * want different things. On a desktop the default {@code ~/.mindconnect}
 * is right: one person, one home, beside what the launcher keeps there. On
 * a server that would be the service account's file for everybody, so put
 * {@code {user}} in the template and each user gets their own directory,
 * e.g. {@code /srv/mindconnect/users/{user}}. The template is
 * {@code mindconnect.agent.instructions.user-dir}; {@code off} turns the
 * user scope off altogether.
 *
 * <p>The file name is {@code AGENTS.md} first: an open specification since
 * 2025, stewarded by the Linux Foundation's Agentic AI Foundation and read
 * by a couple of dozen coding tools, so a repository that already has one
 * needs nothing new here. {@code PROMPT.md} is for a project that wants a
 * file of its own, and {@code CLAUDE.md} comes last so a repository set up
 * for Claude Code is not left silent. The first that exists wins; they are
 * not concatenated, because two of them usually say the same thing twice.
 *
 * <p>For the project scope only the working directory itself is searched,
 * not its parents. That directory is the one the user picked with the
 * chat's folder button, so reading from it is something they asked for;
 * walking up would reach into directories they never chose.
 */
public final class InstructionFileReader {

    private static final Logger log = LoggerFactory.getLogger(InstructionFileReader.class);

    /** Tried in this order; the first that exists is the one that is read. */
    static final List<String> FILE_NAMES = List.of("AGENTS.md", "PROMPT.md", "CLAUDE.md");

    /** The placeholder a per-user template carries. */
    static final String USER_PLACEHOLDER = "{user}";

    /** The value that turns the user scope off. */
    public static final String OFF = "off";

    /**
     * Past this the file stops being instructions and starts being a
     * document. Cut rather than dropped: the opening of such a file is
     * still worth more to the model than nothing.
     */
    public static final int MAX_CHARS = 20_000;

    /** {@code null} when the user scope is off. */
    private final String userDirTemplate;

    private InstructionFileReader(String userDirTemplate) {
        this.userDirTemplate = userDirTemplate;
    }

    /**
     * With the given user directory: a plain path, or one carrying
     * {@link #USER_PLACEHOLDER} for a directory per user. Blank falls back
     * to {@link #defaultUserDir()}; {@link #OFF} means no user scope.
     */
    public static InstructionFileReader of(String userDirTemplate) {
        if (userDirTemplate == null || userDirTemplate.isBlank()) {
            return new InstructionFileReader(defaultUserDir());
        }
        String template = userDirTemplate.strip();
        return new InstructionFileReader(OFF.equalsIgnoreCase(template) ? null : template);
    }

    /** Project instructions only. */
    public static InstructionFileReader projectOnly() {
        return new InstructionFileReader(null);
    }

    /** {@code ~/.mindconnect}, or {@code null} for an account without a home. */
    public static String defaultUserDir() {
        String home = System.getProperty("user.home");
        return home == null || home.isBlank() ? null : home + "/.mindconnect";
    }

    /** The user's standing instructions, or an empty string when there are none. */
    public String userSection(String userId) {
        Path dir = userDir(userId);
        Found found = firstIn(dir);
        if (found == null) return "";
        return section("User instructions",
                "From `" + found.name() + "` in `" + dir + "`. These are the user's own standing "
                        + "instructions and hold in every project. Where the project below disagrees, "
                        + "the project wins.",
                found);
    }

    /**
     * The instructions of the project in that working directory, or an empty
     * string when there is none or the directory holds no
     * such file.
     */
    public String projectSection(String workingDir) {
        if (workingDir == null || workingDir.isBlank()) return "";
        Found found = firstIn(pathOf(workingDir));
        if (found == null) return "";
        return section("Project instructions",
                "From `" + found.name() + "` in the working directory. This is the project speaking, "
                        + "not the user: follow it while working here, and say so if the user asks "
                        + "for something it rules out.",
                found);
    }

    /**
     * The directory that user's instructions live in, or
     * {@code null} when the scope is off or the user id cannot safely fill
     * the placeholder.
     */
    public Path userDir(String userId) {
        if (userDirTemplate == null) return null;
        if (!userDirTemplate.contains(USER_PLACEHOLDER)) return pathOf(userDirTemplate);
        if (!isSafeSegment(userId)) {
            log.debug("No user instructions: '{}' is not usable as a directory name", userId);
            return null;
        }
        return pathOf(userDirTemplate.replace(USER_PLACEHOLDER, userId));
    }

    /** A user id may name one directory, and must not climb out of it. */
    private static boolean isSafeSegment(String userId) {
        return userId != null && !userId.isBlank()
                && userId.indexOf('/') < 0 && userId.indexOf('\\') < 0
                && !userId.contains("..") && !userId.equals(".");
    }

    private static Path pathOf(String value) {
        try {
            return Path.of(value);
        } catch (InvalidPathException e) {
            return null;
        }
    }

    /** The first readable, non-empty instruction file in the directory. */
    private static Found firstIn(Path dir) {
        if (dir == null) return null;
        for (String name : FILE_NAMES) {
            Path file = dir.resolve(name);
            if (!Files.isRegularFile(file)) continue;
            String content = read(file);
            if (content == null || content.isBlank()) continue;
            return new Found(name, content);
        }
        return null;
    }

    /** The rendered section: a heading, where it came from, then the file itself. */
    public static String section(String heading, String provenance, Found found) {
        String body = found.content().strip();
        String note = "";
        if (body.length() > MAX_CHARS) {
            body = body.substring(0, MAX_CHARS);
            note = "\n\n[…] " + found.name() + " is longer than " + MAX_CHARS
                    + " characters and was cut here.";
        }
        return "\n\n## " + heading + "\n" + provenance + "\n\n" + body + note;
    }

    private static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException | RuntimeException e) {
            // An unreadable or undecodable file is not worth failing a turn for.
            log.debug("Instructions at {} could not be read: {}", file, e.toString());
            return null;
        }
    }

    /** A file that was there and had something in it. */
    public record Found(String name, String content) {}
}
