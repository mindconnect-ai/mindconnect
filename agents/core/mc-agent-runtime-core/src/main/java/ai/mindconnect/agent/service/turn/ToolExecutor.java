package ai.mindconnect.agent.service.turn;

import ai.mindconnect.agent.domain.StreamEvent;
import ai.mindconnect.agent.port.out.TokenCounter;
import ai.mindconnect.agent.tool.Tool;
import ai.mindconnect.agent.tool.ToolAdvisor;
import ai.mindconnect.common.LoggingContext;
import ai.mindconnect.common.Namespace;
import ai.mindconnect.llm.domain.ToolCall;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Wraps tool invocation: MDC scope, wire logging, stream events, lookup,
 * try/catch, and duration measurement.
 *
 * <p>Stateless singleton — per-turn data flows in through {@link Context} on
 * each invocation. Persistence of TOOL_CALL / TOOL_RESULT messages is the
 * caller's responsibility via {@code ChatTurnContext.addMessage(...)}.
 */
public class ToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(ToolExecutor.class);
    /**
     * Verbose tool wire log. Activate via
     * {@code logging.level.ai.mindconnect.agent.tool=DEBUG} (or TRACE) — when
     * active, every invocation logs full args on entry and full result on exit;
     * otherwise only a one-line summary.
     */
    private static final Logger toolWire = LoggerFactory.getLogger("ai.mindconnect.agent.tool");

    /**
     * Per-call context: everything that varies per turn. The extra
     * {@code namespace}/{@code userId}/{@code sessionId} fields are
     * forwarded to advisors via {@link ToolAdvisor.Invocation}; callers
     * that don't use advisors can pass {@code null} for them — the
     * {@link Tool#execute} tail of the chain doesn't read them.
     */
    public record Context(Consumer<StreamEvent> stream,
                          UUID conversationId,
                          UUID agentId,
                          TokenCounter counter,
                          Namespace namespace,
                          String userId,
                          UUID sessionId) {

        /** Backward-compat ctor for callers that don't carry advisor context yet. */
        public Context(Consumer<StreamEvent> stream, UUID conversationId,
                       UUID agentId, TokenCounter counter) {
            this(stream, conversationId, agentId, counter, null, null, null);
        }
    }

    /**
     * Sorted, immutable list of advisors that wrap every {@link #execute}
     * call. Empty = same behaviour as before the advisor port existed.
     */
    private final List<ToolAdvisor> advisors;

    /** Construct without advisors — preserves old behaviour. */
    public ToolExecutor() {
        this(List.of());
    }

    public ToolExecutor(List<ToolAdvisor> advisors) {
        this.advisors = advisors == null || advisors.isEmpty()
                ? List.of()
                : advisors.stream()
                    .sorted(Comparator.comparingInt(ToolAdvisor::order))
                    .toList();
    }

    /** Result: text the LLM should see, and the wall-clock duration of the call. */
    public record Result(String resultText, long durationMs) {}

    /**
     * Executes a single tool call and returns its result string plus the
     * measured duration. Never throws — tool failures are converted into a
     * descriptive error string so the LLM can react to it. On failure a
     * {@link StreamEvent.ToolCallFailed} is emitted instead of
     * {@link StreamEvent.ToolCallResult}, but the LLM still sees the error
     * string as a regular tool result via the returned {@link Result}.
     */
    public Result execute(ToolCall tc, List<Tool> tools, Context ctx) {
        long startMs = System.currentTimeMillis();
        try (var ignored = LoggingContext.tool(tc.name())) {
            logEntry(tc);
            ctx.stream().accept(new StreamEvent.ToolCallStarted(tc.name(), tc.arguments()));
            Invocation inv = invokeTool(tc, tools, ctx);
            long durationMs = System.currentTimeMillis() - startMs;
            if (inv.failed()) {
                ctx.stream().accept(new StreamEvent.ToolCallFailed(tc.name(), inv.text(), durationMs));
            } else {
                ctx.stream().accept(new StreamEvent.ToolCallResult(tc.name(), inv.text(), durationMs));
            }
            return new Result(inv.text(), durationMs);
        }
    }

    /**
     * Internal: invocation outcome.
     *
     * @param text   text the LLM sees (regular result on success, error description on failure)
     * @param failed {@code true} when the tool was unknown or threw — drives which stream
     *               event is emitted
     */
    private record Invocation(String text, boolean failed) {}

    private void logEntry(ToolCall tc) {
        if (toolWire.isDebugEnabled()) {
            toolWire.debug("→ {} args={}", tc.name(), tc.arguments());
        } else {
            log.info("Executing tool '{}' (args: {} keys)", tc.name(),
                    tc.arguments() != null ? tc.arguments().size() : 0);
        }
    }

    private void logExit(ToolCall tc, String result) {
        if (toolWire.isDebugEnabled()) {
            toolWire.debug("← {} result ({} chars):\n{}", tc.name(), result.length(), result);
        } else {
            log.info("Tool '{}' result: {} chars", tc.name(), result.length());
        }
    }

    private Invocation invokeTool(ToolCall tc, List<Tool> tools, Context ctx) {
        Optional<Tool> toolOpt = tools.stream()
                .filter(t -> t.name().equals(tc.name())).findFirst();
        if (toolOpt.isEmpty()) {
            log.error("Tool '{}' was called by the LLM but is not available (not registered or missing configuration)", tc.name());
            // Hand the model the list of tools it actually has so it can
            // self-correct on the next step instead of repeating the made-up
            // name. Weaker models in particular tend to hallucinate tool names
            // from their training data (e.g. "find_in_file").
            String available = tools.stream().map(Tool::name).sorted().collect(Collectors.joining(", "));
            return new Invocation(
                    "Unknown tool: '" + tc.name() + "'. This tool does not exist. "
                            + "Only call tools from this exact list: [" + available + "].",
                    true);
        }

        ToolAdvisor.Invocation advisorInv = new ToolAdvisor.Invocation(
                tc.name(),
                tc.arguments(),
                ctx.namespace(),
                ctx.userId(),
                ctx.sessionId(),
                ctx.agentId(),
                tc.id());

        // Build the chain: each advisor wraps the next one; the tail runs
        // the real Tool.execute. We assemble back-to-front so order()=lowest
        // ends up outermost.
        ToolAdvisor.Chain chain = currentInv -> {
            try {
                String r = toolOpt.get().execute(currentInv.arguments());
                logExit(tc, r);
                return ToolAdvisor.Result.ok(r);
            } catch (Exception e) {
                log.error("Tool '{}' threw an exception", tc.name(), e);
                return ToolAdvisor.Result.error(
                        "Error executing tool " + tc.name() + ": " + e.getMessage());
            }
        };
        for (int i = advisors.size() - 1; i >= 0; i--) {
            ToolAdvisor advisor = advisors.get(i);
            ToolAdvisor.Chain inner = chain;
            chain = currentInv -> {
                if (!advisor.applies(currentInv)) return inner.proceed(currentInv);
                try {
                    return advisor.around(currentInv, inner);
                } catch (Exception e) {
                    log.error("Advisor {} threw — falling through to inner chain",
                            advisor.getClass().getSimpleName(), e);
                    return inner.proceed(currentInv);
                }
            };
        }

        try {
            ToolAdvisor.Result r = chain.proceed(advisorInv);
            return new Invocation(r.text(), r.failed());
        } catch (Exception e) {
            // Should be unreachable — both the tail and every advisor wrapper
            // swallow exceptions above. Keep a final guard so the runtime
            // never sees an uncaught throw from here.
            log.error("Advisor chain threw — converting to error result", e);
            return new Invocation(
                    "Error executing tool " + tc.name() + ": " + e.getMessage(), true);
        }
    }

}
