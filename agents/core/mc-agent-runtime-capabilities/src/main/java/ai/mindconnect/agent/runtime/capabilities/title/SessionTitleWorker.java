package ai.mindconnect.agent.runtime.capabilities.title;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import ai.mindconnect.agent.loop.domain.LlmAnswer;
import ai.mindconnect.agent.loop.domain.TurnMessage;
import ai.mindconnect.agent.runtime.api.domain.ModelRequest;
import ai.mindconnect.agent.runtime.api.domain.ModelSettings;
import ai.mindconnect.agent.runtime.api.domain.Session;
import ai.mindconnect.agent.runtime.api.domain.SessionEvent;
import ai.mindconnect.agent.runtime.api.domain.SessionId;
import ai.mindconnect.agent.runtime.api.domain.TurnId;
import ai.mindconnect.agent.runtime.api.port.out.BackgroundWorker;
import ai.mindconnect.agent.runtime.api.port.out.ModelGateway;
import ai.mindconnect.agent.runtime.api.port.out.SessionEventPublisher;
import ai.mindconnect.agent.runtime.api.port.out.SessionStore;

/** Asks the model for a title of a session's first exchange and keeps it on the session. */
final class SessionTitleWorker implements BackgroundWorker {

    static final String NAME = "session-title";
    static final String SESSION_ID = "sessionId";
    static final String TITLE = "title";
    static final String TITLE_FOUND = "session.title";
    private static final String INSTRUCTIONS = "Give the conversation a title of at most six words, in the "
            + "language it is held in. Answer with the title alone, without quotes or a full stop.";

    private final Supplier<SessionStore> sessions;
    private final Supplier<ModelGateway> model;
    private final Supplier<SessionEventPublisher> events;

    SessionTitleWorker(Supplier<SessionStore> sessions, Supplier<ModelGateway> model,
                       Supplier<SessionEventPublisher> events) {
        this.sessions = sessions;
        this.model = model;
        this.events = events;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public void run(Map<String, Object> payload) {
        SessionId sessionId = SessionId.of(String.valueOf(payload.get(SESSION_ID)));
        Session session = sessions.get().find(sessionId).orElse(null);
        // Run twice, it finds the title already there.
        if (session == null || session.metadata().containsKey(TITLE)) {
            return;
        }
        List<TurnMessage> exchange = sessions.get().messages(sessionId).stream()
                .filter(message -> message.type() == TurnMessage.Type.USER
                        || message.type() == TurnMessage.Type.ASSISTANT)
                .limit(2)
                .toList();
        LlmAnswer answer = model.get().ask(new ModelRequest(TurnId.of(NAME + "-" + sessionId.value()),
                session.agent() == null ? ModelSettings.NONE : session.agent().modelSettings(), INSTRUCTIONS, exchange, List.of()), event -> { });
        String title = answer.messages().stream().map(TurnMessage::text).findFirst().orElse("").strip();
        if (title.isEmpty()) {
            return;
        }
        Map<String, Object> metadata = new HashMap<>(session.metadata());
        metadata.put(TITLE, title);
        sessions.get().save(new Session(session.id(), session.userId(), session.parentId(),
                session.agent(), metadata, session.createdAt()));
        // A list of sessions on screen wants the title when it is there, not at the next reload.
        events.get().publish(new SessionEvent.Notice(sessionId, null, TITLE_FOUND, Map.of(TITLE, title)));
    }
}
