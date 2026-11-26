package ai.mindconnect.agent.cli.local.file;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import ai.mindconnect.agent.runtime.api.domain.Session;

/**
 * Where a file harness looks: the user's home and the working directory of
 * the session at hand, each with a {@code .mc} folder for agents, skills and
 * settings. The working directory is the stronger of the two — a project
 * says how it wants to be worked on, the home says how the user usually
 * works.
 *
 * <p>A session's working directory is kept in its metadata; a session
 * without one has only the home. Everything here is read off the session
 * that is handed in — this class keeps nothing but where the home is.
 */
public final class HarnessDirectories {

    /** The session attribute that holds the working directory, as an absolute path. */
    public static final String WORKING_DIRECTORY = "cwd";

    private static final String CONFIG_FOLDER = ".mc";

    private final Path home;

    public HarnessDirectories(Path home) {
        this.home = home;
    }

    public Optional<Path> workingDirectory(Session session) {
        return session.metadata().get(WORKING_DIRECTORY) instanceof String directory
                ? Optional.of(Path.of(directory))
                : Optional.empty();
    }

    /** The {@code .mc} folders that apply, strongest first: the working directory's, then the home's. */
    public List<Path> configDirectories(Session session) {
        return workingDirectory(session).map(this::configDirectories)
                .orElseGet(() -> List.of(home.resolve(CONFIG_FOLDER)));
    }

    /** The same for a working directory no session has yet: where the agent of a new session is looked up. */
    public List<Path> configDirectories(Path workingDirectory) {
        return List.of(workingDirectory.resolve(CONFIG_FOLDER), home.resolve(CONFIG_FOLDER));
    }

    /** The strongest {@code .mc} folder — where something this session decides is written. */
    public Path writableConfigDirectory(Session session) {
        return configDirectories(session).get(0);
    }

    /**
     * A file inside the session's working directory. A path that leads out
     * of it is refused: a tool works on the project, not on the machine.
     */
    public Path workspaceFile(Session session, String relativePath) {
        Path workspace = workingDirectory(session).orElseThrow(() ->
                new IllegalStateException("Session " + session.id() + " has no working directory"))
                .toAbsolutePath().normalize();
        Path file = workspace.resolve(relativePath).normalize();
        if (!file.startsWith(workspace)) {
            throw new IllegalArgumentException(relativePath + " is outside the working directory");
        }
        return file;
    }

    /** Whether a file belongs to the harness' own folder and is none of a tool's business. */
    public static boolean isConfigFile(Path workspace, Path file) {
        return workspace.relativize(file).startsWith(CONFIG_FOLDER);
    }
}
