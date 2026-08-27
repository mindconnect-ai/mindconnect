package ai.mindconnect.agent.adapter.file;

import ai.mindconnect.agent.domain.AgentSession;
import ai.mindconnect.agent.port.out.AgentSessionRepository;
import ai.mindconnect.common.Namespace;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Stores sessions under:
 *   {base}/users/{userId}/sessions/{sessionId}/session.json
 */
@Component
public class FileAgentSessionRepository implements AgentSessionRepository {

    private static final Logger log = Logger.getLogger(FileAgentSessionRepository.class.getName());
    private static final String FILE_NAME = "session.json";

    private final Path baseDir;
    private final ObjectMapper objectMapper;

    public FileAgentSessionRepository(Path agentStorageDir, ObjectMapper objectMapper) {
        this.baseDir = agentStorageDir.toAbsolutePath();
        this.objectMapper = objectMapper;
        log.info("AgentSessionRepository base: " + this.baseDir);
    }

    @Override
    public AgentSession save(AgentSession session) {
        Path file = fileFor(session);
        try {
            Files.createDirectories(file.getParent());
            objectMapper.writeValue(file.toFile(), session);
            return session;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public Optional<AgentSession> findById(UUID id) {
        // We don't know the userId without scanning; do a broad search.
        // In practice this is called after listing sessions (so userId is known),
        // but we support the generic lookup by scanning users/ dirs.
        Path usersDir = baseDir.resolve("users");
        if (!Files.exists(usersDir)) return Optional.empty();
        try (var userStream = Files.list(usersDir)) {
            return userStream
                    .filter(Files::isDirectory)
                    .flatMap(userDir -> {
                        Path sessionsDir = userDir.resolve("sessions");
                        if (!Files.exists(sessionsDir)) return java.util.stream.Stream.empty();
                        try {
                            return Files.list(sessionsDir);
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    })
                    .filter(Files::isDirectory)
                    .filter(d -> d.getFileName().toString().equals(id.toString()))
                    .map(d -> d.resolve(FILE_NAME))
                    .filter(Files::exists)
                    .map(f -> {
                        try {
                            return objectMapper.readValue(f.toFile(), AgentSession.class);
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    })
                    .findFirst();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public List<AgentSession> findByAgentDefinitionId(UUID agentDefinitionId, Namespace namespace, String userId) {
        Path sessionsDir = userSessionsDir(userId);
        if (!Files.exists(sessionsDir)) return List.of();
        try (var stream = Files.list(sessionsDir)) {
            return stream
                    .filter(Files::isDirectory)
                    .map(d -> d.resolve(FILE_NAME))
                    .filter(Files::exists)
                    .map(f -> {
                        try {
                            return objectMapper.readValue(f.toFile(), AgentSession.class);
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    })
                    .filter(s -> s.agentDefinitionId().equals(agentDefinitionId)
                            && s.namespace().equals(namespace)
                            && s.userId().equals(userId))
                    .sorted((a, b) -> b.startedAt().compareTo(a.startedAt()))
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public List<AgentSession> findByParentSessionId(UUID parentSessionId) {
        // Sub-agent sessions can live under any user's sessions/ dir, so we
        // walk every user. The session.json files are tiny so the scan is
        // cheap; this is called from the trace UI to show sub-agent calls.
        Path usersDir = baseDir.resolve("users");
        if (!Files.exists(usersDir)) return List.of();
        try (var userStream = Files.list(usersDir)) {
            return userStream
                    .filter(Files::isDirectory)
                    .flatMap(userDir -> {
                        Path sessionsDir = userDir.resolve("sessions");
                        if (!Files.exists(sessionsDir)) return java.util.stream.Stream.empty();
                        try {
                            return Files.list(sessionsDir);
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    })
                    .filter(Files::isDirectory)
                    .map(d -> d.resolve(FILE_NAME))
                    .filter(Files::exists)
                    .map(f -> {
                        try {
                            return objectMapper.readValue(f.toFile(), AgentSession.class);
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    })
                    .filter(s -> parentSessionId.equals(s.parentSessionId()))
                    .sorted(java.util.Comparator.comparing(AgentSession::startedAt))
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void deleteById(UUID id) {
        Path usersDir = baseDir.resolve("users");
        if (!Files.exists(usersDir)) return;
        try (var userStream = Files.list(usersDir)) {
            userStream
                    .filter(Files::isDirectory)
                    .map(u -> u.resolve("sessions").resolve(id.toString()))
                    .filter(Files::isDirectory)
                    .findFirst()
                    .ifPresent(sessionDir -> {
                        try {
                            deleteRecursively(sessionDir);
                            log.info("Deleted session directory: " + sessionDir);
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private Path fileFor(AgentSession session) {
        return userSessionsDir(session.userId())
                .resolve(session.id().toString())
                .resolve(FILE_NAME);
    }

    private Path userSessionsDir(String userId) {
        return baseDir.resolve("users").resolve(sanitize(userId)).resolve("sessions");
    }

    private String sanitize(String value) {
        return value.replaceAll("[^a-zA-Z0-9_\\-]", "_");
    }

    private void deleteRecursively(Path dir) throws IOException {
        try (var stream = Files.walk(dir)) {
            stream.sorted(java.util.Comparator.reverseOrder())
                    .map(Path::toFile)
                    .forEach(java.io.File::delete);
        }
    }
}
