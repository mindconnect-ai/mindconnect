package ai.mindconnect.agent.tools.workspace;

import java.util.Optional;

/**
 * Abstraction over workspace storage.
 *
 * Implementations may store content on the local filesystem (FileWorkspaceStore),
 * in S3, or in a database — without any other code needing to change.
 *
 * Each entry is addressed by a {@link WorkspaceScope} (which scope) and a
 * filename (e.g. "summary.md", "notes.md", "memory.json").
 */
public interface WorkspaceStore {

    /** Write (create or overwrite) a file in the given scope. */
    void write(WorkspaceScope scope, String filename, String content);

    /** Read a file, returning empty if it does not exist. */
    Optional<String> read(WorkspaceScope scope, String filename);

    /** Delete a file if it exists. */
    void delete(WorkspaceScope scope, String filename);

    /** Returns true if the file exists in the given scope. */
    boolean exists(WorkspaceScope scope, String filename);

    /** Lists all filenames in the given scope. Returns an empty list if none exist. */
    java.util.List<String> list(WorkspaceScope scope);

    /**
     * Raw bytes for binary-safe download. Default implementation falls back to
     * {@link #read} (UTF-8 round-trip), which is fine for text but lossy for
     * truly binary files — adapters that can do better (the file-based one)
     * should override.
     */
    default Optional<byte[]> readBytes(WorkspaceScope scope, String filename) {
        return read(scope, filename).map(s -> s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /**
     * File size in bytes, if the file exists. Default implementation falls back
     * to {@link #readBytes} (loads the full content), so adapters with cheap
     * stat access should override.
     */
    default Optional<Long> sizeOf(WorkspaceScope scope, String filename) {
        return readBytes(scope, filename).map(b -> (long) b.length);
    }

    // ── convenience defaults ──────────────────────────────────────────────────

    /** Read a file or return null if absent (matches legacy WorkspaceService.readIfExists semantics). */
    default String readOrNull(WorkspaceScope scope, String filename) {
        return read(scope, filename).orElse(null);
    }
}
