package ai.mindconnect.agent.runtime.infrastructure;

import java.util.function.Supplier;

import ai.mindconnect.agent.SessionId;
import ai.mindconnect.agent.runtime.api.domain.TurnId;
import ai.mindconnect.agent.runtime.api.port.in.TurnService;
import ai.mindconnect.agent.trace.domain.LlmCallTrace;
import ai.mindconnect.agent.trace.domain.TraceContext;
import ai.mindconnect.agent.runtime.llm.LlmCallTraceListener;
import ai.mindconnect.agent.trace.port.out.LlmCallTraceRepository;
import ai.mindconnect.llm.runtime.api.domain.LlmCallEvent;

/**
 * Every provider call of a turn into the application's trace repository,
 * request and response as the gateway saw them — what the traces page shows.
 * A call made for no turn, the session title's, is not traced.
 */
final class RepositoryLlmCallTraceListener implements LlmCallTraceListener {

    private final LlmCallTraceRepository traces;
    private final Supplier<TurnService> turns;

    RepositoryLlmCallTraceListener(LlmCallTraceRepository traces, Supplier<TurnService> turns) {
        this.traces = traces;
        this.turns = turns;
    }

    @Override
    public void onCall(TurnId turnId, LlmCallEvent event) {
        turns.get().find(turnId).ifPresent(turn -> traces.save(LlmCallTrace.of(
                new TraceContext(turn.sessionId(), turnId.value(), null, 0, turn.agent().name()),
                event)));
    }
}
