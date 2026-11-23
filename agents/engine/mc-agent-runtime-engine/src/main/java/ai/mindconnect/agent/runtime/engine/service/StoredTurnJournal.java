package ai.mindconnect.agent.runtime.engine.service;

import java.util.List;
import java.util.function.BooleanSupplier;

import ai.mindconnect.agent.loop.domain.TurnMessage;
import ai.mindconnect.agent.loop.port.out.TurnJournal;
import ai.mindconnect.agent.runtime.api.domain.SessionId;
import ai.mindconnect.agent.runtime.api.port.out.SessionStore;

/**
 * The loop's journal of one turn: the session's messages in the
 * {@link SessionStore}, and the cancel as the turn's task knows it. Rounds
 * and their cost are counted from the messages, as the journal does by
 * default.
 */
final class StoredTurnJournal implements TurnJournal {

    private final SessionStore sessions;
    private final SessionId sessionId;
    private final BooleanSupplier cancelRequested;

    StoredTurnJournal(SessionStore sessions, SessionId sessionId, BooleanSupplier cancelRequested) {
        this.sessions = sessions;
        this.sessionId = sessionId;
        this.cancelRequested = cancelRequested;
    }

    @Override
    public List<TurnMessage> load() {
        return sessions.messages(sessionId);
    }

    @Override
    public List<TurnMessage> append(List<TurnMessage> messages) {
        return sessions.append(sessionId, messages);
    }

    @Override
    public boolean cancelRequested() {
        return cancelRequested.getAsBoolean();
    }
}
