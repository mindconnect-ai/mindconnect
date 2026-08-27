package ai.mindconnect.agent.adapter.file;

import ai.mindconnect.agent.tools.workspace.WorkspaceScope;
import ai.mindconnect.agent.tools.workspace.WorkspaceStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Filesystem-backed WorkspaceStore.
 *
 * Directory layout mirrors the three scope types:
 *   {base}/users/{userId}/workspace/
 *   {base}/users/{userId}/agents/{agentId}/workspace/
 *   {base}/users/{userId}/sessions/{sessionId}/workspace/
 *
 * Swap for an S3 or JPA implementation without touching any service code.
 */
public class FileWorkspaceStore implements WorkspaceStore {

    private static final Logger log = LoggerFactory.getLogger(FileWorkspaceStore.class);

    private final Path baseDir;

    public FileWorkspaceStore(Path baseDir) {
        this.baseDir = baseDir.toAbsolutePath().normalize();
    }

    /** Exposes the base directory so the file-based impl can hand paths to agents via system prompt. */
    public Path baseDir() {
        return baseDir;
    }

    /** Returns the resolved directory for a scope (creates it if absent). */
    public Path scopeDir(WorkspaceScope scope) {
        return ensureDir(resolveDir(scope));
    }

    @Override
    public void write(WorkspaceScope scope, String filename, String content) {
        Path file = scopeDir(scope).resolve(filename);
        try {
            Files.writeString(file, content);
            log.debug("WorkspaceStore.write: {}", file);
        } catch (IOException e) {
            log.warn("WorkspaceStore.write failed for {}: {}", file, e.getMessage());
        }
    }

    @Override
    public Optional<String> read(WorkspaceScope scope, String filename) {
        Path file = resolveDir(scope).resolve(filename);
        if (!Files.exists(file)) return Optional.empty();
        try {
            return Optional.of(Files.readString(file).strip());
        } catch (IOException e) {
            log.warn("WorkspaceStore.read failed for {}: {}", file, e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public void delete(WorkspaceScope scope, String filename) {
        Path file = resolveDir(scope).resolve(filename);
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            log.warn("WorkspaceStore.delete failed for {}: {}", file, e.getMessage());
        }
    }

    @Override
    public boolean exists(WorkspaceScope scope, String filename) {
        return Files.exists(resolveDir(scope).resolve(filename));
    }

    @Override
    public Optional<byte[]> readBytes(WorkspaceScope scope, String filename) {
        Path file = resolveDir(scope).resolve(filename);
        if (!Files.exists(file) || Files.isDirectory(file)) return Optional.empty();
        try {
            return Optional.of(Files.readAllBytes(file));
        } catch (IOException e) {
            log.warn("WorkspaceStore.readBytes failed for {}: {}", file, e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public Optional<Long> sizeOf(WorkspaceScope scope, String filename) {
        Path file = resolveDir(scope).resolve(filename);
        if (!Files.exists(file) || Files.isDirectory(file)) return Optional.empty();
        try {
            return Optional.of(Files.size(file));
        } catch (IOException e) {
            log.warn("WorkspaceStore.sizeOf failed for {}: {}", file, e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public java.util.List<String> list(WorkspaceScope scope) {
        Path dir = resolveDir(scope);
        if (!Files.exists(dir)) return java.util.List.of();
        try (var stream = Files.list(dir)) {
            return stream
                    .filter(p -> !Files.isDirectory(p))
                    .map(p -> p.getFileName().toString())
                    .sorted()
                    .toList();
        } catch (IOException e) {
            log.warn("WorkspaceStore.list failed for scope {}: {}", scope.type(), e.getMessage());
            return java.util.List.of();
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private Path resolveDir(WorkspaceScope scope) {
        return switch (scope.type()) {
            case USER       -> baseDir
                                .resolve("users")
                                .resolve(sanitize(scope.userId()))
                                .resolve("workspace");
            case AGENT_USER -> baseDir
                                .resolve("users")
                                .resolve(sanitize(scope.userId()))
                                .resolve("agents")
                                .resolve(scope.agentId().toString())
                                .resolve("workspace");
            case SESSION    -> baseDir
                                .resolve("users")
                                .resolve(sanitize(scope.userId()))
                                .resolve("sessions")
                                .resolve(scope.sessionId().toString())
                                .resolve("workspace");
        };
    }

    private Path ensureDir(Path dir) {
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            log.warn("WorkspaceStore: could not create directory {}: {}", dir, e.getMessage());
        }
        return dir;
    }

    private String sanitize(String value) {
        return value.replaceAll("[^a-zA-Z0-9_\\-]", "_");
    }
}
