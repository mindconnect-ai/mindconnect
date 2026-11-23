package ai.mindconnect.agent.starter.runtime.bridge;

import java.util.function.Supplier;

import ai.mindconnect.agent.SessionId;
import ai.mindconnect.agent.runtime.api.domain.TurnId;
import ai.mindconnect.agent.runtime.api.port.in.TurnService;
import ai.mindconnect.agent.runtime.api.port.out.SessionStore;
import ai.mindconnect.agent.runtime.engine.adapter.store.RepositorySessionStore;
import ai.mindconnect.agent.runtime.domain.LlmCallTrace;
import ai.mindconnect.agent.runtime.domain.TraceContext;
import ai.mindconnect.agent.runtime.engine.adapter.llm.LlmCallTraceListener;
import ai.mindconnect.agent.runtime.port.out.LlmCallTraceRepository;
import ai.mindconnect.llm.domain.LlmCallEvent;
import ai.mindconnect.message.domain.ChatTurnId;
import ai.mindconnect.message.domain.ConversationId;

/**
 * Every provider call of a turn into the application's trace repository,
 * request and response as the gateway saw them — what the traces page shows.
 * A call made for no turn, the session title's, is not traced.
 */
final class RepositoryLlmCallTraceListener implements LlmCallTraceListener {

    private final LlmCallTraceRepository traces;
    private final Supplier<TurnService> turns;
    private final Supplier<SessionStore> sessions;

    RepositoryLlmCallTraceListener(LlmCallTraceRepository traces, Supplier<TurnService> turns,
                                   Supplier<SessionStore> sessions) {
        this.traces = traces;
        this.turns = turns;
        this.sessions = sessions;
    }

    @Override
    public void onCall(TurnId turnId, LlmCallEvent event) {
        turns.get().find(turnId).ifPresent(turn -> traces.save(LlmCallTrace.of(
                new TraceContext(
                        // Filed under the conversation the session's messages are in: where the traces page looks.
                        sessions.get().find(turn.sessionId()).map(RepositorySessionStore::conversationOf)
                                .orElseGet(() -> ConversationId.of(turn.sessionId().value())),
                        SessionId.of(turn.sessionId().value()),
                        ChatTurnId.of(turnId.value()),
                        null, 0, turn.agent().name()),
                event)));
    }
}
