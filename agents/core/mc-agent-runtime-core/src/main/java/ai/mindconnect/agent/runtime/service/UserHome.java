package ai.mindconnect.agent.runtime.service;

import ai.mindconnect.agent.SessionId;
import ai.mindconnect.agent.UserId;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * A user's directory on the server — the one place their sessions, uploads
 * and, by default, their working directories live. Configured as a path
 * template with {@value WorkingDirPolicy#USER_PLACEHOLDER} in it
 * ({@code mindconnect.users.home}, by default {@code <data-dir>/<namespace>/home/{user}});
 * a user's home is created the first time it is asked for.
 *
 * <pre>
 * &lt;home&gt;/                    the user's directory — also the default root a working directory must lie under
 *   sessions/&lt;session-id&gt;/    a session's own directory: its working directory unless it chose another
 *     uploads/                the files attached to the session, as the file tools can read them
 * </pre>
 *
 * <p>A runtime without one — the CLI on the user's own machine, an
 * embedding that never set a data directory — answers empty everywhere, and
 * a session then simply has no directory of its own.
 */
public final class UserHome {

    private final String template;

    private UserHome(String template) {
        this.template = template;
    }

    /** A home under {@code template} — a path with {@code {user}} in it; {@code null} or blank for none. */
    public static UserHome of(String template) {
        if (template == null || template.isBlank()) return none();
        String t = template.trim();
        if (!t.contains(WorkingDirPolicy.USER_PLACEHOLDER)) {
            throw new IllegalArgumentException("A users' home needs " + WorkingDirPolicy.USER_PLACEHOLDER
                    + " in it, so every user gets one of their own: " + t);
        }
        return new UserHome(t);
    }

    /** The conventional home under a namespace's data directory: {@code <dataDir>/home/{user}}. */
    public static UserHome under(Path dataDir) {
        return dataDir == null ? none()
                : of(dataDir.resolve("home").resolve(WorkingDirPolicy.USER_PLACEHOLDER).toString());
    }

    /** No home for anyone. */
    public static UserHome none() {
        return new UserHome(null);
    }

    public boolean isConfigured() {
        return template != null;
    }

    /** The template as configured, {@code {user}} and all; {@code null} when none. */
    public String template() {
        return template;
    }

    /** The user's home, created on first use; empty without one. */
    public Optional<Path> homeOf(UserId userId) {
        Path home = homePath(userId);
        return home == null ? Optional.empty() : Optional.of(ensure(home));
    }

    /** A session's own directory under the user's home, created on first use; empty without a home. */
    public Optional<Path> sessionDirOf(UserId userId, SessionId sessionId) {
        if (sessionId == null) return Optional.empty();
        return homeOf(userId).map(home -> ensure(home.resolve("sessions").resolve(sessionId.value())));
    }

    /**
     * A session's own directory as it would be, without creating anything —
     * for telling a directory the session chose apart from the one it was
     * given. Empty without a home.
     */
    public Optional<Path> existingSessionDirOf(UserId userId, SessionId sessionId) {
        Path home = homePath(userId);
        if (home == null || sessionId == null) return Optional.empty();
        Path dir = home.resolve("sessions").resolve(sessionId.value());
        return Files.isDirectory(dir) ? Optional.of(real(dir)) : Optional.empty();
    }

    /** Where a session's attached files are put for the file tools to read, created on first use. */
    public Optional<Path> uploadsDirOf(UserId userId, SessionId sessionId) {
        return sessionDirOf(userId, sessionId).map(dir -> ensure(dir.resolve("uploads")));
    }

    /** The user's home as a path, nothing created; {@code null} without a home or a user. */
    private Path homePath(UserId userId) {
        if (template == null || userId == null || userId.value() == null || userId.value().isBlank()) return null;
        return WorkingDirPolicy.expand(template.replace(WorkingDirPolicy.USER_PLACEHOLDER,
                WorkingDirPolicy.pathSafe(userId.value()))).toAbsolutePath().normalize();
    }

    private static Path ensure(Path dir) {
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot create " + dir + ": " + e.getMessage(), e);
        }
        return real(dir);
    }

    /** Real path — the form the working-dir policy answers in, so the two compare equal. */
    private static Path real(Path dir) {
        try {
            return dir.toRealPath();
        } catch (IOException e) {
            return dir;
        }
    }
}
