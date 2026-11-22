package ai.mindconnect.agent.loop.service;

import java.util.ArrayList;
import java.util.List;

import ai.mindconnect.agent.loop.domain.LlmAnswer;
import ai.mindconnect.agent.loop.domain.RoundOutcome;
import ai.mindconnect.agent.loop.domain.ToolCalls;
import ai.mindconnect.agent.loop.domain.ToolResult;
import ai.mindconnect.agent.loop.domain.TurnMessage;
import ai.mindconnect.agent.loop.domain.Usage;
import ai.mindconnect.agent.loop.port.in.AgentRound;
import ai.mindconnect.agent.loop.port.out.AgentRoundToolExecutor;
import ai.mindconnect.agent.loop.port.out.LlmProvider;
import ai.mindconnect.agent.loop.port.out.TurnEventListener;

/**
 * {@link AgentRound} over the two ports a round needs: either it asks the
 * {@link LlmProvider}, or it advances the open tool calls through the
 * {@link AgentRoundToolExecutor}. Never both — that keeps every round a step
 * the caller can persist, stream and resume.
 *
 * <pre>
 * 1. Open tool calls?  → dispatch, collect results
 * 2. None open?        → ask the model
 * </pre>
 *
 * <p>Cancellation needs nothing here: it is checked between rounds, and into
 * the one long model call the {@link LlmProvider} implementation carries it.
 * Neither do tool definitions: which tools the model is offered is part of
 * the window that implementation renders.
 */
public final class DefaultAgentRound implements AgentRound {

    private final LlmProvider llm;
    private final AgentRoundToolExecutor agentRoundToolExecutor;

    public DefaultAgentRound(LlmProvider llm, AgentRoundToolExecutor agentRoundToolExecutor) {
        this.llm = llm;
        this.agentRoundToolExecutor = agentRoundToolExecutor;
    }

    @Override
    public RoundOutcome execute(String requestId, String sessionId, List<TurnMessage> history,
                                TurnEventListener events) {
        ToolCalls toolCalls = ToolCalls.of(ToolCalls.episode(history));
        return toolCalls.allDone()
                ? askModel(requestId, sessionId, history, events)
                : advanceCalls(requestId, sessionId, toolCalls);
    }

    @Override
    public void cancel(String requestId) {
        llm.cancel(requestId);
        agentRoundToolExecutor.cancel(requestId);
    }

    private RoundOutcome askModel(String requestId, String sessionId, List<TurnMessage> history,
                                  TurnEventListener events) {
        LlmAnswer answer = llm.ask(requestId, sessionId, history, events);
        return new RoundOutcome(answer.messages(), outcomeOf(answer), answer.usage(), List.of());
    }

    private static RoundOutcome.Outcome outcomeOf(LlmAnswer answer) {
        if (answer.truncated()) return RoundOutcome.Outcome.TRUNCATED;
        boolean callsRequested = answer.messages().stream()
                .anyMatch(m -> m.type() == TurnMessage.Type.TOOL_CALL);
        return callsRequested ? RoundOutcome.Outcome.CALLS_REQUESTED : RoundOutcome.Outcome.ANSWERED;
    }

    private RoundOutcome advanceCalls(String requestId, String sessionId, ToolCalls toolCalls) {
        List<TurnMessage> added = new ArrayList<>();
        List<String> running = new ArrayList<>();
        boolean advanced = false;

        for (ToolCalls.Call call : toolCalls.open()) {
            switch (call.state()) {
                case RUNNABLE -> {
                    // Marker BEFORE the dispatch reaches anyone — the caller
                    // persists `added` in this order before the next round.
                    // Approval is the TOOL TASK's business (the gate): a
                    // gated call is dispatched like any other and simply
                    // takes as long as the human takes.
                    added.add(TurnMessage.dispatched(call.callId()));
                    agentRoundToolExecutor.execute(requestId, sessionId, call);
                    advanced = true;
                }
                case RUNNING -> {
                    TurnMessage output = collect(sessionId, call);
                    if (output == null) {
                        running.add(call.callId());
                    } else {
                        added.add(output);
                        advanced = true;
                    }
                }
                case DONE -> { }
            }
        }
        RoundOutcome.Outcome outcome = advanced
                ? RoundOutcome.Outcome.TOOLS_ADVANCED
                : RoundOutcome.Outcome.WAITING_FOR_TOOLS;
        return new RoundOutcome(added, outcome, Usage.ZERO, running);
    }

    /** Collect a result. {@code null} means: still running, the next round asks again. */
    private TurnMessage collect(String sessionId, ToolCalls.Call call) {
        return switch (agentRoundToolExecutor.result(sessionId, call.callId())) {
            case ToolResult.Finished finished ->
                    TurnMessage.toolResult(call.callId(), call.name(), finished.output(), finished.failed())
                            .with("durationMs", finished.durationMs());
            case ToolResult.Lost lost -> TurnMessage.toolResult(call.callId(), call.name(),
                    "Error: tool call was interrupted before completion: " + lost.reason(), true);
            case ToolResult.Running ignored -> null;
        };
    }
}
