package ai.mindconnect.agent.runtime.adapter.file;

import ai.mindconnect.agent.Namespace;
import ai.mindconnect.agent.AgentId;
import ai.mindconnect.agent.SessionId;
import ai.mindconnect.agent.UserId;

import ai.mindconnect.agent.runtime.domain.AgentSession;
import ai.mindconnect.agent.runtime.port.out.AgentSessionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Optional;
import java.util.logging.Logger;

/**
 * Stores sessions under:
 *   {base}/users/{userId}/sessions/{sessionId}/session.json
 */
public class FileAgentSessionRepository implements AgentSessionRepository {

    private static final Logger log = Logger.getLogger(FileAgentSessionRepository.class.getName());
    private static final String FILE_NAME = "session.json";

    private final Path baseDir;
    private final ObjectMapper objectMapper;

    public FileAgentSessionRepository(Path agentStorageDir, ObjectMapper objectMapper, Namespace namespace) {
        this.baseDir = agentStorageDir.resolve(namespace.value()).toAbsolutePath();
        this.objectMapper = objectMapper;
        log.info("AgentSessionRepository base: " + this.baseDir);
    }

    /**
     * Newest first, and tolerant of a session without a start time: one
     * unreadable timestamp should misplace a single row, not throw and take
     * the user's whole session list with it.
     */
    private static final java.util.Comparator<AgentSession> NEWEST_FIRST =
            java.util.Comparator.comparing(AgentSession::startedAt,
                    java.util.Comparator.nullsLast(java.util.Comparator.reverseOrder()));

    @Override
    public AgentSession save(AgentSession session) {
        Path file = fileFor(session);
        try {
            Files.createDirectories(file.getParent());
            // Written beside the target and moved over it in one step: a session is
            // read while other threads save it (tool tasks, title generation), and a
            // reader must never see the half-written file.
            Path tmp = Files.createTempFile(file.getParent(), FILE_NAME + ".", ".tmp");
            try {
                objectMapper.writeValue(tmp.toFile(), session);
                Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(tmp);
            }
            return session;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public Optional<AgentSession> findById(SessionId id) {
        return allSessionDirs()
                .filter(d -> d.getFileName().toString().equals(id.value()))
                .map(d -> d.resolve(FILE_NAME))
                .filter(Files::exists)
                .map(f -> read(f))
                .filter(s -> s.id().equals(id))
                .findFirst();
    }

    @Override
    public List<AgentSession> findByAgent(AgentId agent, UserId user) {
        return userSessions(user)
                .filter(s -> s.agentDefinitionId().equals(agent) && s.userId().equals(user))
                .sorted(NEWEST_FIRST)
                .toList();
    }

    @Override
    public List<AgentSession> findByUser(UserId user) {
        return userSessions(user)
                .filter(s -> s.userId().equals(user)
                        && s.parentSessionId() == null)
                .sorted(NEWEST_FIRST)
                .toList();
    }

    @Override
    public List<AgentSession> findByParentSession(SessionId parent) {
        return allSessionDirs()
                .map(d -> d.resolve(FILE_NAME))
                .filter(Files::exists)
                .map(f -> read(f))
                .filter(s -> parent.equals(s.parentSessionId()))
                .sorted(java.util.Comparator.comparing(AgentSession::startedAt))
                .toList();
    }

    @Override
    public void deleteById(SessionId id) {
        findById(id).ifPresent(session -> {
            Path sessionDir = userSessionsDir(session.userId()).resolve(id.value());
            if (!Files.isDirectory(sessionDir)) return;
            try {
                deleteRecursively(sessionDir);
                log.info("Deleted session directory: " + sessionDir);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
    }

    // ── layout: users/<user>/sessions/<session>/session.json ────────────────

    /** Every session directory of every user. */
    private java.util.stream.Stream<Path> allSessionDirs() {
        Path usersDir = baseDir.resolve("users");
        if (!Files.exists(usersDir)) return java.util.stream.Stream.empty();
        try (var userStream = Files.list(usersDir)) {
            return userStream
                    .filter(Files::isDirectory)
                    .flatMap(userDir -> {
                        Path sessionsDir = userDir.resolve("sessions");
                        if (!Files.exists(sessionsDir)) return java.util.stream.Stream.empty();
                        try (var sessions = Files.list(sessionsDir)) {
                            return sessions.filter(Files::isDirectory).toList().stream();
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    })
                    .toList().stream();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The sessions under one user's directory, read in {@code namespace}. */
    private java.util.stream.Stream<AgentSession> userSessions(UserId user) {
        Path sessionsDir = userSessionsDir(user);
        if (!Files.exists(sessionsDir)) return java.util.stream.Stream.empty();
        try (var stream = Files.list(sessionsDir)) {
            return stream
                    .filter(Files::isDirectory)
                    .map(d -> d.resolve(FILE_NAME))
                    .filter(Files::exists)
                    .map(f -> read(f))
                    .toList().stream();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Reads a session in {@code namespace}; one written before the namespace was recorded takes it from here. */
    private AgentSession read(Path file) {
        try {
            return objectMapper.readerFor(AgentSession.class)
                    .readValue(file.toFile());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private Path fileFor(AgentSession session) {
        return userSessionsDir(session.userId())
                .resolve(session.id().value())
                .resolve(FILE_NAME);
    }

    private Path userSessionsDir(UserId user) {
        return baseDir.resolve("users").resolve(sanitize(user.value())).resolve("sessions");
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
