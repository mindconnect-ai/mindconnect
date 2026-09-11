package ai.mindconnect.agent.runtime.adapter.file;

import ai.mindconnect.agent.Namespace;
import ai.mindconnect.agent.SessionId;

import ai.mindconnect.agent.runtime.memory.domain.WorkingMemory;
import ai.mindconnect.agent.runtime.memory.port.out.WorkingMemoryRepository;
import ai.mindconnect.agent.AuthenticationInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Stores internal session data under:
 *   {base}/users/{userId}/sessions/{sessionId}/working-memory.json
 *   {base}/users/{userId}/sessions/{sessionId}/summary.md
 *
 * No in-memory index needed — userId is taken from {@link AuthenticationInfo}.
 */
public class FileWorkingMemoryRepository implements WorkingMemoryRepository {

    private static final Logger log = LoggerFactory.getLogger(FileWorkingMemoryRepository.class);
    private static final String MEMORY_FILE  = "working-memory.json";
    private static final String SUMMARY_FILE = "summary.md";

    private final Path baseDir;
    private final ObjectMapper mapper = new ObjectMapper();

    public FileWorkingMemoryRepository(Path baseDir, Namespace namespace) {
        this.baseDir = baseDir.resolve(namespace.value()).toAbsolutePath().normalize();
    }

    @Override
    public void save(SessionId sessionId, AuthenticationInfo auth, WorkingMemory memory) {
        Path file = sessionDir(auth.userId().value(), sessionId).resolve(MEMORY_FILE);
        try {
            Files.createDirectories(file.getParent());
            mapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), memory);
            log.debug("Saved working memory for session {} ({} tokens)", sessionId, memory.totalTokens());
        } catch (IOException e) {
            log.warn("Failed to save working memory for session {}: {}", sessionId, e.getMessage());
        }
    }

    @Override
    public Optional<WorkingMemory> findBySession(SessionId sessionId, AuthenticationInfo auth) {
        Path file = sessionDir(auth.userId().value(), sessionId).resolve(MEMORY_FILE);
        if (!Files.exists(file)) return Optional.empty();
        try {
            return Optional.of(mapper.readValue(file.toFile(), WorkingMemory.class));
        } catch (IOException e) {
            log.warn("Failed to read working memory for session {}: {}", sessionId, e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public void delete(SessionId sessionId, AuthenticationInfo auth) {
        deleteFile(sessionDir(auth.userId().value(), sessionId).resolve(MEMORY_FILE));
        deleteFile(sessionDir(auth.userId().value(), sessionId).resolve(SUMMARY_FILE));
    }

    @Override
    public void saveSummary(SessionId sessionId, AuthenticationInfo auth, String summary) {
        Path file = sessionDir(auth.userId().value(), sessionId).resolve(SUMMARY_FILE);
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, summary);
            log.debug("Saved summary for session {}", sessionId);
        } catch (IOException e) {
            log.warn("Failed to save summary for session {}: {}", sessionId, e.getMessage());
        }
    }

    @Override
    public Optional<String> loadSummary(SessionId sessionId, AuthenticationInfo auth) {
        Path file = sessionDir(auth.userId().value(), sessionId).resolve(SUMMARY_FILE);
        if (!Files.exists(file)) return Optional.empty();
        try {
            String content = Files.readString(file).strip();
            return content.isBlank() ? Optional.empty() : Optional.of(content);
        } catch (IOException e) {
            log.warn("Failed to read summary for session {}: {}", sessionId, e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public void deleteSummary(SessionId sessionId, AuthenticationInfo auth) {
        deleteFile(sessionDir(auth.userId().value(), sessionId).resolve(SUMMARY_FILE));
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private Path sessionDir(String userId, SessionId sessionId) {
        return baseDir
                .resolve("users")
                .resolve(sanitize(userId))
                .resolve("sessions")
                .resolve(sessionId.value());
    }

    private void deleteFile(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            log.warn("Failed to delete {}: {}", file, e.getMessage());
        }
    }

    private static String sanitize(String value) {
        return value.replaceAll("[^a-zA-Z0-9_\\-]", "_");
    }
}
