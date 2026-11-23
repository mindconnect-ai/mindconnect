package ai.mindconnect.agent.runtime.engine.adapter.store;

import java.nio.file.Path;
import java.util.Optional;
import java.util.function.UnaryOperator;

import com.fasterxml.jackson.databind.ObjectMapper;

import ai.mindconnect.agent.runtime.api.domain.Session;
import ai.mindconnect.agent.runtime.api.domain.SessionId;
import ai.mindconnect.agent.runtime.api.port.out.NamespaceProvider;
import ai.mindconnect.agent.runtime.engine.port.out.SessionRepository;
import ai.mindconnect.filerepo.DocumentExistsException;
import ai.mindconnect.filerepo.Documents;
import ai.mindconnect.filerepo.FileRepo;

/**
 * Sessions under {@code {base}/{namespace}/sessions/{sessionId}/session.json}.
 * The path follows from the session id alone, which is what lets
 * {@link #update} lock exactly this session's file — reads take no lock,
 * and updates of one session take turns (see {@code mc-file-repo}).
 */
public final class FileSessionRepository implements SessionRepository {

    private final Documents<SessionId, Session> sessions;

    public FileSessionRepository(Path base, ObjectMapper json, NamespaceProvider namespaces) {
        this.sessions = Documents.of(Session.class)
                .path((SessionId id) -> "sessions/" + id.value() + "/session.json")
                // The partition of the namespace the current call works in.
                .build(() -> FileRepo.open(base, namespaces.current()), json);
    }

    @Override
    public Session create(Session session) {
        try {
            return sessions.create(session.id(), session);
        } catch (DocumentExistsException e) {
            throw new IllegalStateException("Session " + session.id().value() + " exists already", e);
        }
    }

    @Override
    public Optional<Session> find(SessionId id) {
        return sessions.find(id);
    }

    @Override
    public Optional<Session> update(SessionId id, UnaryOperator<Session> change) {
        return sessions.update(id, change);
    }
}
