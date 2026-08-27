package ai.mindconnect.agent.adapter.file;

import ai.mindconnect.agent.memory.domain.WorkingMemory;
import ai.mindconnect.agent.memory.port.out.WorkingMemoryRepository;
import ai.mindconnect.common.AuthenticationInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;

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

    public FileWorkingMemoryRepository(Path baseDir) {
        this.baseDir = baseDir.toAbsolutePath().normalize();
    }

    @Override
    public void save(UUID sessionId, AuthenticationInfo auth, WorkingMemory memory) {
        Path file = sessionDir(auth.userId(), sessionId).resolve(MEMORY_FILE);
        try {
            Files.createDirectories(file.getParent());
            mapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), memory);
            log.debug("Saved working memory for session {} ({} tokens)", sessionId, memory.totalTokens());
        } catch (IOException e) {
            log.warn("Failed to save working memory for session {}: {}", sessionId, e.getMessage());
        }
    }

    @Override
    public Optional<WorkingMemory> findBySessionId(UUID sessionId, AuthenticationInfo auth) {
        Path file = sessionDir(auth.userId(), sessionId).resolve(MEMORY_FILE);
        if (!Files.exists(file)) return Optional.empty();
        try {
            return Optional.of(mapper.readValue(file.toFile(), WorkingMemory.class));
        } catch (IOException e) {
            log.warn("Failed to read working memory for session {}: {}", sessionId, e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public void delete(UUID sessionId, AuthenticationInfo auth) {
        deleteFile(sessionDir(auth.userId(), sessionId).resolve(MEMORY_FILE));
        deleteFile(sessionDir(auth.userId(), sessionId).resolve(SUMMARY_FILE));
    }

    @Override
    public void saveSummary(UUID sessionId, AuthenticationInfo auth, String summary) {
        Path file = sessionDir(auth.userId(), sessionId).resolve(SUMMARY_FILE);
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, summary);
            log.debug("Saved summary for session {}", sessionId);
        } catch (IOException e) {
            log.warn("Failed to save summary for session {}: {}", sessionId, e.getMessage());
        }
    }

    @Override
    public Optional<String> loadSummary(UUID sessionId, AuthenticationInfo auth) {
        Path file = sessionDir(auth.userId(), sessionId).resolve(SUMMARY_FILE);
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
    public void deleteSummary(UUID sessionId, AuthenticationInfo auth) {
        deleteFile(sessionDir(auth.userId(), sessionId).resolve(SUMMARY_FILE));
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private Path sessionDir(String userId, UUID sessionId) {
        return baseDir
                .resolve("users")
                .resolve(sanitize(userId))
                .resolve("sessions")
                .resolve(sessionId.toString());
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
