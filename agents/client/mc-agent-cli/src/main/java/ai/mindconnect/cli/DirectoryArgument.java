package ai.mindconnect.cli;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;

/**
 * A directory as typed after {@code /cd} or {@code /add-dir}. Whoever
 * validates it — the local runtime or a remote server — resolves a relative
 * path against its own process directory, which is not what the user means:
 * {@code /cd ..} is meant from where the session works. So a relative path
 * is resolved here, against the session's working directory, before it is
 * sent.
 */
final class DirectoryArgument {

    private DirectoryArgument() {}

    /**
     * {@code input} resolved against {@code workingDir} and normalised when it
     * is relative and the session has a working directory. Anything else goes
     * as typed (trimmed): an absolute path, one starting with {@code ~} or
     * {@code $HOME} (expanded where it is validated), a blank, or a relative
     * path in a session without a working directory.
     */
    static String resolve(String input, String workingDir) {
        if (input == null || input.isBlank()) return input;
        String dir = input.trim();
        if (workingDir == null || workingDir.isBlank() || dir.startsWith("~") || dir.startsWith("$HOME")) {
            return dir;
        }
        try {
            Path path = Path.of(dir);
            if (path.isAbsolute()) return dir;
            return Path.of(workingDir).resolve(path).normalize().toString();
        } catch (InvalidPathException e) {
            // Not a path here; let the validator say what is wrong with it.
            return dir;
        }
    }
}
