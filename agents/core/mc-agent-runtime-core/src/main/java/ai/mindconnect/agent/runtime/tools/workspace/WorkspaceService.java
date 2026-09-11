package ai.mindconnect.agent.runtime.tools.workspace;

import ai.mindconnect.agent.SessionId;
import ai.mindconnect.agent.UserId;
import ai.mindconnect.agent.AgentId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

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

    public Path userWorkspace(UserId userId) {
        return ensure(baseDir.resolve("user").resolve(sanitize(userId.value())));
    }

    public Path agentUserWorkspace(AgentId agentId, UserId userId) {
        return ensure(baseDir.resolve("agent")
                .resolve(agentId.value())
                .resolve(sanitize(userId.value())));
    }

    public Path sessionWorkspace(AgentId agentId, UserId userId, SessionId sessionId) {
        return ensure(baseDir.resolve("session")
                .resolve(agentId.value())
                .resolve(sanitize(userId.value()))
                .resolve(sessionId.value()));
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
