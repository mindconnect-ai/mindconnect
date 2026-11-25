package ai.mindconnect.agent.runtime.capabilities.title;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import ai.mindconnect.agent.loop.domain.ContentPart;
import ai.mindconnect.agent.loop.domain.LlmAnswer;
import ai.mindconnect.agent.loop.domain.TurnMessage;
import ai.mindconnect.agent.runtime.api.domain.ModelRequest;
import ai.mindconnect.agent.runtime.api.domain.BackgroundModel;
import ai.mindconnect.agent.runtime.api.domain.ModelSettings;
import ai.mindconnect.agent.runtime.api.domain.Session;
import ai.mindconnect.agent.runtime.api.domain.SessionEvent;
import ai.mindconnect.agent.SessionId;
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
    private static final String INSTRUCTIONS = "You name conversations. You are shown the start of one. Do not "
            + "answer it and do not continue it: give it a title of at most six words, in the language it is "
            + "held in. Answer with the title alone, without quotes or a full stop.";
    /** A model that answers the conversation instead of naming it must not make a paragraph the title. */
    private static final int LONGEST_TITLE = 80;

    private final Supplier<SessionStore> sessions;
    private final Supplier<ModelGateway> model;
    private final Supplier<SessionEventPublisher> events;
    private final Supplier<Optional<BackgroundModel>> background;

    SessionTitleWorker(Supplier<SessionStore> sessions, Supplier<ModelGateway> model,
                       Supplier<SessionEventPublisher> events, Supplier<Optional<BackgroundModel>> background) {
        this.sessions = sessions;
        this.model = model;
        this.events = events;
        this.background = background;
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
        // Shown as one text and not as the messages themselves: handed the exchange as a conversation,
        // a model goes on with it instead of naming it.
        String exchange = sessions.get().messages(sessionId).stream()
                .filter(message -> message.type() == TurnMessage.Type.USER
                        || message.type() == TurnMessage.Type.ASSISTANT)
                .limit(2)
                .map(message -> message.type().name().toLowerCase(Locale.ROOT) + ": " + message.text())
                .collect(Collectors.joining("\n\n", "The conversation starts like this:\n\n", ""));
        LlmAnswer answer = model.get().ask(new ModelRequest(TurnId.of(NAME + "-" + sessionId.value()),
                background.get().map(BackgroundModel::settings).orElseGet(() ->
                        session.agent() == null ? ModelSettings.NONE : session.agent().modelSettings()),
                INSTRUCTIONS,
                List.of(TurnMessage.user(List.of(new ContentPart.Text(exchange)))), List.of()), event -> { });
        String found = answer.messages().stream().map(TurnMessage::text).findFirst().orElse("").strip();
        String title = found.length() <= LONGEST_TITLE ? found : found.substring(0, LONGEST_TITLE).strip() + "…";
        if (title.isEmpty()) {
            return;
        }
        sessions.get().update(sessionId, stored -> stored.withMetadata(TITLE, title));
        // A list of sessions on screen wants the title when it is there, not at the next reload.
        events.get().publish(new SessionEvent(TITLE_FOUND, sessionId, null, null, Map.of(TITLE, title)));
    }
}
