package ai.mindconnect.agent.tools.workspace;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/**
 * Manages the three workspace scopes for agents:
 *
 *   {base}/user/{userId}/                        — cross-agent user profile
 *   {base}/agent/{agentId}/{userId}/             — persistent agent↔user memory
 *   {base}/session/{agentId}/{userId}/{sessionId}/ — session scratch space
 */
public class WorkspaceService {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceService.class);

    private final Path baseDir;

    public WorkspaceService(Path baseDir) {
        this.baseDir = baseDir.toAbsolutePath().normalize();
    }

    public Path userWorkspace(String userId) {
        return ensure(baseDir.resolve("user").resolve(sanitize(userId)));
    }

    public Path agentUserWorkspace(UUID agentId, String userId) {
        return ensure(baseDir.resolve("agent")
                .resolve(agentId.toString())
                .resolve(sanitize(userId)));
    }

    public Path sessionWorkspace(UUID agentId, String userId, UUID sessionId) {
        return ensure(baseDir.resolve("session")
                .resolve(agentId.toString())
                .resolve(sanitize(userId))
                .resolve(sessionId.toString()));
    }

    public Path baseDir() {
        return baseDir;
    }

    /** Read a file if it exists, return null otherwise. */
    public String readIfExists(Path file) {
        if (!Files.exists(file)) return null;
        try {
            return Files.readString(file).strip();
        } catch (IOException e) {
            log.warn("Failed to read workspace file {}: {}", file, e.getMessage());
            return null;
        }
    }

    private Path ensure(Path dir) {
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            log.warn("Failed to create workspace dir {}: {}", dir, e.getMessage());
        }
        return dir;
    }

    private String sanitize(String value) {
        return value.replaceAll("[^a-zA-Z0-9_\\-]", "_");
    }
}
