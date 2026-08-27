package ai.mindconnect.agent.service.round;

import ai.mindconnect.common.Cancellation;
import ai.mindconnect.message.domain.Message;
import ai.mindconnect.message.domain.MessageType;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * One turn of the agent loop's crank — one call, one round.
 *
 * <p>Who turns the rounds is {@link AgentLoop}, one level up. Here stands only
 * what ONE round does; everything between two rounds — persisting, publishing,
 * cancelling, counting — belongs there.
 *
 * <p>A round does exactly one thing: either it asks the model, or it advances
 * the open tool calls. Never both — that keeps every round a step the caller
 * can persist, stream and resume.
 *
 * <pre>
 * 1. Open tool calls?  → dispatch, collect results, request approvals
 * 2. None open?        → ask the model
 * 3. The caller turns while {@link RoundOutcome.Outcome#continues()}
 * </pre>
 *
 * <p>The round holds no state: everything it needs to know is in the message
 * list that comes in — {@link ToolCalls} folds it, episode-locally. That is
 * why the caller may do whatever it wants between rounds: persist, stream,
 * compact old tool results, cancel. One rule binds the caller: <b>shorten
 * yes, remove only when no call is open</b> — openness is derived, so a
 * removed TOOL_RESULT re-opens a finished call and the tool would run again.
 *
 * <p>Cancellation needs nothing here: it is checked between rounds, and into
 * the one long model call the {@link LlmProvider} implementation carries it.
 */
public class AgentRound {

    private final LlmProvider llm;
    private final ToolDefinitionProvider toolDefinitions;
    private final AgentRoundToolExecutor agentRoundToolExecutor;

    public AgentRound(LlmProvider llm, ToolDefinitionProvider toolDefinitions,
                      AgentRoundToolExecutor agentRoundToolExecutor) {
        this.llm = llm;
        this.toolDefinitions = toolDefinitions;
        this.agentRoundToolExecutor = agentRoundToolExecutor;
    }

    /**
     * One round over the FULL history. The fold itself is episode-local
     * ({@link ToolCalls#episode}), the model call gets everything — old calls
     * stay visible as context, only never executed again.
     *
     * @param requestId identifies the whole turn (the user's request); also the
     *                  name under which the ports publish their events
     */
    public RoundOutcome execute(String requestId, UUID sessionId, List<Message> history,
                                Cancellation cancellation) {
        ToolCalls toolCalls = ToolCalls.of(ToolCalls.episode(history));
        return toolCalls.allDone()
                ? askModel(requestId, sessionId, history, cancellation)
                : advanceCalls(requestId, sessionId, toolCalls);
    }

    private RoundOutcome askModel(String requestId, UUID sessionId, List<Message> history,
                                  Cancellation cancellation) {
        LlmAnswer answer = llm.ask(requestId, sessionId, history,
                toolDefinitions.toolDefinitions(sessionId), cancellation);
        return new RoundOutcome(answer.messages(), outcomeOf(answer), answer.usage());
    }

    private static RoundOutcome.Outcome outcomeOf(LlmAnswer answer) {
        if (answer.truncated()) return RoundOutcome.Outcome.TRUNCATED;
        boolean callsRequested = answer.messages().stream()
                .anyMatch(m -> m.type() == MessageType.TOOL_CALL);
        return callsRequested ? RoundOutcome.Outcome.CALLS_REQUESTED : RoundOutcome.Outcome.ANSWERED;
    }

    private RoundOutcome advanceCalls(String requestId, UUID sessionId, ToolCalls toolCalls) {
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
    private TurnMessage collect(UUID sessionId, ToolCalls.Call call) {
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
