package ai.mindconnect.agent.tool.workspace;

import ai.mindconnect.agent.tool.FileRoots;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Where the file tools read and write. The tools keep working with
 * {@link Path} values, but only lexically — resolving, relativising, matching
 * globs — and send every access to the disk through here. That is what lets
 * the same {@code file_read}, {@code grep} or {@code file_edit} work on the
 * machine the agent runs on ({@link LocalWorkspaceFiles}) or on a workspace
 * that lives on another server.
 *
 * <p>{@link #roots()} decides which paths a tool may name, exactly as before;
 * every method here takes a path that {@link FileRoots#resolve} returned.
 */
public interface WorkspaceFiles {

    /** The roots paths resolve against; the base is the working directory the tools describe. */
    FileRoots roots();

    /**
     * Tells workspaces apart that show the same roots — every remote one is
     * {@code /workspace} — for caches keyed by path.
     */
    default String identity() {
        return roots().describe();
    }

    /** The entry for {@code path}, or empty when nothing is there. */
    Optional<WorkspaceEntry> stat(Path path) throws IOException;

    /** The directory's direct children, in no particular order. */
    List<WorkspaceEntry> list(Path directory) throws IOException;

    /**
     * Walks the tree below {@code start} depth-first. {@code excludedDirectoryNames}
     * are skipped wherever they occur below the start, before the walker sees them;
     * an implementation that walks remotely prunes them on the other side.
     */
    void walk(Path start, java.util.Set<String> excludedDirectoryNames, WorkspaceWalker walker) throws IOException;

    byte[] readAllBytes(Path file) throws IOException;

    /** At most {@code maxBytes} from the start of the file, e.g. to tell text from binary. */
    byte[] readHead(Path file, int maxBytes) throws IOException;

    /** Writes the whole file, creating missing parent directories. */
    void write(Path file, byte[] content) throws IOException;

    /**
     * Deletes a file, a link (never what it points to) or a directory with everything
     * in it. A workspace that cannot delete says so with {@link UnsupportedOperationException}.
     */
    default void delete(Path path) throws IOException {
        throw new UnsupportedOperationException("This workspace cannot delete files");
    }

    /**
     * The file as a path on this machine, for libraries that only read from a
     * {@link Path} (document parsers). Local workspaces hand out the file itself;
     * remote ones a temporary copy that closing deletes.
     */
    LocalFile localFile(Path file) throws IOException;

    /** A file on this machine; close it when done. */
    interface LocalFile extends AutoCloseable {
        Path path();

        @Override
        void close();
    }

    // ---- conveniences over stat -------------------------------------------------------------

    default boolean exists(Path path) {
        try {
            return stat(path).isPresent();
        } catch (IOException e) {
            return false;
        }
    }

    default boolean isDirectory(Path path) {
        try {
            return stat(path).map(WorkspaceEntry::directory).orElse(false);
        } catch (IOException e) {
            return false;
        }
    }

    default boolean isRegularFile(Path path) {
        try {
            return stat(path).map(WorkspaceEntry::regularFile).orElse(false);
        } catch (IOException e) {
            return false;
        }
    }

    default long lastModifiedMillis(Path path) {
        try {
            return stat(path).map(WorkspaceEntry::lastModifiedMillis).orElse(0L);
        } catch (IOException e) {
            return 0L;
        }
    }

    /** The files of this machine under {@code roots}. */
    static WorkspaceFiles local(FileRoots roots) {
        return new LocalWorkspaceFiles(roots);
    }
}
