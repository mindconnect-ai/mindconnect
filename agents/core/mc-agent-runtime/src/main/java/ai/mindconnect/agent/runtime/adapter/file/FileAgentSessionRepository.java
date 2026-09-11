package ai.mindconnect.agent.runtime.adapter.file;

import ai.mindconnect.agent.AgentId;
import ai.mindconnect.agent.Namespace;
import ai.mindconnect.agent.SessionId;
import ai.mindconnect.agent.UserId;
import ai.mindconnect.agent.runtime.domain.AgentSession;
import ai.mindconnect.agent.runtime.port.out.AgentSessionRepository;
import ai.mindconnect.filerepo.DocumentExistsException;
import ai.mindconnect.filerepo.Documents;
import ai.mindconnect.filerepo.FileRepo;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.UnaryOperator;
import java.util.logging.Logger;
import java.util.stream.Stream;

/**
 * Stores sessions under {@code {base}/{namespace}/sessions/{sessionId}/session.json}.
 *
 * <p>The directory belongs to the session: its working memory and its workspace
 * live beside {@code session.json}, and deleting the session deletes the
 * directory. The path follows from the session id alone, which is what lets
 * {@link #update} lock exactly this session's file — reads take no lock, and
 * updates of one session take turns (see {@code mc-file-repo}).
 *
 * <p>Sessions stored under the earlier layout, {@code users/{userId}/sessions/},
 * are not read; the repository logs once when it finds them.
 */
public class FileAgentSessionRepository implements AgentSessionRepository {

    private static final Logger log = Logger.getLogger(FileAgentSessionRepository.class.getName());
    private static final String SESSIONS = "sessions";
    private static final String FILE_NAME = "session.json";

    /**
     * Newest first, and tolerant of a session without a start time: one
     * unreadable timestamp should misplace a single row, not throw and take
     * the user's whole session list with it.
     */
    private static final Comparator<AgentSession> NEWEST_FIRST =
            Comparator.comparing(AgentSession::startedAt, Comparator.nullsLast(Comparator.reverseOrder()));

    private static final Comparator<AgentSession> OLDEST_FIRST =
            Comparator.comparing(AgentSession::startedAt, Comparator.nullsLast(Comparator.naturalOrder()));

    private final FileRepo repo;
    private final Documents<SessionId, AgentSession> sessions;

    public FileAgentSessionRepository(Path agentStorageDir, ObjectMapper objectMapper, Namespace namespace) {
        this.repo = FileRepo.open(agentStorageDir, namespace.value());
        this.sessions = Documents.of(AgentSession.class)
                .path((SessionId id) -> SESSIONS + "/" + id.value() + "/" + FILE_NAME)
                .build(repo, objectMapper);
        log.info("AgentSessionRepository base: " + repo.root());
        warnAboutEarlierLayout();
    }

    @Override
    public AgentSession create(AgentSession session) {
        try {
            return sessions.create(session.id(), session);
        } catch (DocumentExistsException e) {
            throw new IllegalStateException("Session " + session.id().value() + " exists already", e);
        }
    }

    @Override
    public Optional<AgentSession> update(SessionId id, UnaryOperator<AgentSession> change) {
        return sessions.update(id, change);
    }

    @Override
    public Optional<AgentSession> findById(SessionId id) {
        return sessions.find(id);
    }

    @Override
    public List<AgentSession> findByAgent(AgentId agent, UserId user) {
        return all()
                .filter(s -> s.agentDefinitionId().equals(agent) && s.userId().equals(user))
                .sorted(NEWEST_FIRST)
                .toList();
    }

    @Override
    public List<AgentSession> findByUser(UserId user) {
        return all()
                .filter(s -> s.userId().equals(user) && s.parentSessionId() == null)
                .sorted(NEWEST_FIRST)
                .toList();
    }

    @Override
    public List<AgentSession> findByParentSession(SessionId parent) {
        return all()
                .filter(s -> parent.equals(s.parentSessionId()))
                .sorted(OLDEST_FIRST)
                .toList();
    }

    /**
     * Deletes {@code session.json} first — under its lock, so an update running
     * meanwhile finds no session and writes nothing — then the rest of the
     * directory: working memory, workspace.
     */
    @Override
    public void deleteById(SessionId id) {
        if (!sessions.delete(id)) return;
        Path dir = repo.resolve(SESSIONS + "/" + id.value());
        try (Stream<Path> files = Files.walk(dir)) {
            files.sorted(Comparator.reverseOrder()).forEach(FileAgentSessionRepository::deleteQuietly);
            log.info("Deleted session directory: " + dir);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private Stream<AgentSession> all() {
        return sessions.findAll(SESSIONS, FILE_NAME).stream();
    }

    private static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            log.warning("Could not delete " + file + ": " + e.getMessage());
        }
    }

    private void warnAboutEarlierLayout() {
        Path users = repo.resolve("users");
        if (!Files.isDirectory(users)) return;
        try (DirectoryStream<Path> userDirs = Files.newDirectoryStream(users)) {
            for (Path userDir : userDirs) {
                if (Files.isDirectory(userDir.resolve(SESSIONS))) {
                    log.warning("Sessions found under the earlier layout " + users
                            + "/<user>/sessions/ — they are not read any more; sessions now live under "
                            + repo.resolve(SESSIONS));
                    return;
                }
            }
        } catch (IOException e) {
            // only a hint — nothing depends on it
        }
    }
}
