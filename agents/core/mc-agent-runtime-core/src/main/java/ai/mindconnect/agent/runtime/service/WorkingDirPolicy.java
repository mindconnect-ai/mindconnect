package ai.mindconnect.agent.runtime.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;

/**
 * What a session may take as its working directory. The directory has to
 * exist, and — on a server where the runtime's process can see more than
 * the user should — it has to lie under a root the operator chose:
 * {@code mindconnect.tools.working-dir-root}, by default the tools' base
 * directory. The CLI, one user on their own machine, runs unrestricted.
 *
 * <p>A root may carry the placeholder {@code {user}}: then every user has a
 * root of their own ({@code /srv/mindconnect/users/{user}}), created on
 * first use, and sees nothing of anyone else's. Such a policy answers
 * nothing until {@link #forUser} names the user.
 *
 * <p>On a server shared by several users the choice can be taken away
 * altogether ({@code mindconnect.working-dirs.choice: false}, see
 * {@link #withChoice}): then no directory validates, and every chat works in
 * the directory of its own the runtime gives it.
 *
 * <p>A path comes in as the user typed it ({@code ~/src/app}, {@code .},
 * {@code ../other}) and leaves as an absolute, normalised, real path — the
 * one the tools sandbox against, so a symlink into a forbidden place is
 * resolved before the check, not after.
 */
public final class WorkingDirPolicy {

    /** The placeholder in a root that stands for the user's id. */
    public static final String USER_PLACEHOLDER = "{user}";

    /** What {@link #validate} says to a directory while choosing one is switched off. */
    static final String CHOICE_OFF = "Choosing a working directory is switched off here "
            + "(mindconnect.working-dirs.choice); every chat works in its own directory";

    private final Path root;
    private final String template;
    private final boolean choice;

    private WorkingDirPolicy(Path root, String template, boolean choice) {
        this.root = root;
        this.template = template;
        this.choice = choice;
    }

    /** Any existing directory goes. */
    public static WorkingDirPolicy unrestricted() {
        return new WorkingDirPolicy(null, null, true);
    }

    /**
     * The same policy with the user's choice of a directory allowed or not.
     * Without it nothing validates; the directory a runtime assigns a chat
     * itself is not a choice and does not pass through here.
     */
    public WorkingDirPolicy withChoice(boolean allowed) {
        return allowed == choice ? this : new WorkingDirPolicy(root, template, allowed);
    }

    /** May a user choose a chat's directories? */
    public boolean allowsChoice() {
        return choice;
    }

    /** Throws what {@link #validate} would for any directory when choosing one is switched off. */
    public void requireChoice() {
        if (!choice) throw new IllegalArgumentException(CHOICE_OFF);
    }

    /**
     * Only directories under {@code root}; {@code null} or blank means
     * unrestricted. A root containing {@code {user}} is a template — see
     * {@link #forUser}.
     */
    public static WorkingDirPolicy within(String root) {
        if (root == null || root.isBlank()) return unrestricted();
        if (root.contains(USER_PLACEHOLDER)) return new WorkingDirPolicy(null, root.trim(), true);
        return new WorkingDirPolicy(expand(root).toAbsolutePath().normalize(), null, true);
    }

    /** Is the root per user — does it still need {@link #forUser}? */
    public boolean isPerUser() {
        return template != null;
    }

    /**
     * The policy for one user: a per-user root with the user's id filled
     * in and the directory created; any other policy as it is. The id is
     * reduced to letters, digits, dot, dash and underscore for the path.
     */
    public WorkingDirPolicy forUser(String userId) {
        if (template == null) return this;
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("A per-user working-dir root needs a user");
        }
        Path userRoot = expand(template.replace(USER_PLACEHOLDER, pathSafe(userId))).toAbsolutePath().normalize();
        try {
            Files.createDirectories(userRoot);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot create the working-dir root " + userRoot + ": " + e.getMessage(), e);
        }
        return new WorkingDirPolicy(userRoot, null, choice);
    }

    /** The root as configured, or {@code null} when unrestricted (or still a per-user template). */
    public Path root() {
        return root;
    }

    /**
     * The root as the file system knows it — symlinks resolved, the form
     * every validated path comes back in — or {@code null} when
     * unrestricted. A root that does not exist yet stays as configured.
     */
    public Path realRoot() {
        if (root == null) return null;
        try {
            return Files.isDirectory(root) ? root.toRealPath() : root;
        } catch (IOException e) {
            return root;
        }
    }

    /**
     * The directory as the session records it, or an
     * {@link IllegalArgumentException} that says what is wrong with it.
     * {@code null} and blank mean "no working directory" and pass as
     * {@code null}.
     */
    public String validate(String workingDir) {
        if (template != null) {
            throw new IllegalStateException("A per-user working-dir root needs forUser(userId) first");
        }
        if (workingDir == null || workingDir.isBlank()) return null;
        requireChoice();
        Path path;
        try {
            path = expand(workingDir.trim()).toAbsolutePath().normalize();
        } catch (InvalidPathException e) {
            throw new IllegalArgumentException("Not a valid path: " + workingDir);
        }
        if (!Files.isDirectory(path)) {
            throw new IllegalArgumentException("Not a directory: " + path);
        }
        Path real;
        try {
            real = path.toRealPath();
        } catch (IOException e) {
            throw new IllegalArgumentException("Cannot resolve " + path + ": " + e.getMessage());
        }
        if (root != null) {
            Path realRoot = realRoot();
            if (!real.startsWith(realRoot)) {
                throw new IllegalArgumentException("The working directory must lie under " + realRoot
                        + " (mindconnect.tools.working-dir-root): " + real);
            }
        }
        return real.toString();
    }

    /** {@code ~} and {@code $HOME} at the start of a path stand for the user's home. */
    static Path expand(String path) {
        String home = System.getProperty("user.home");
        if (path.equals("~") || path.equals("$HOME")) return Path.of(home);
        if (path.startsWith("~/")) return Path.of(home, path.substring(2));
        if (path.startsWith("$HOME/")) return Path.of(home, path.substring("$HOME/".length()));
        return Path.of(path);
    }

    /** A user id as one path segment: anything but letters, digits, dot, dash and underscore becomes an underscore. */
    static String pathSafe(String userId) {
        String safe = userId.trim().replaceAll("[^A-Za-z0-9._-]", "_");
        while (safe.startsWith(".")) safe = "_" + safe.substring(1);
        return safe.isEmpty() ? "_" : safe;
    }

    /** Same as {@link #forUser(String)}, for a typed user id. */
    public WorkingDirPolicy forUser(ai.mindconnect.agent.UserId userId) {
        return forUser(userId == null ? null : userId.value());
    }
}
