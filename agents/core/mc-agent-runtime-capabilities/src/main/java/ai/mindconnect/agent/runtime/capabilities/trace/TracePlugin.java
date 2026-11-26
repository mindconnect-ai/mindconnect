package ai.mindconnect.agent.runtime.capabilities.trace;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.function.Function;

import ai.mindconnect.agent.loop.domain.LlmAnswer;
import ai.mindconnect.agent.runtime.api.domain.ToolOutput;
import ai.mindconnect.agent.runtime.api.hook.AgentContext;
import ai.mindconnect.agent.runtime.api.hook.RuntimeHook;
import ai.mindconnect.plugin.Plugin;
import ai.mindconnect.plugin.PluginRegistry;

/**
 * Traces: every model call and every tool call is written down, with what
 * it cost and how it ended. Wrapped around the work rather than listening
 * after it, because a trace needs the duration and must exist for a call
 * that threw.
 */
public final class TracePlugin implements Plugin {

    private final TraceSink sink;

    public TracePlugin(TraceSink sink) {
        this.sink = sink;
    }

    @Override
    public String name() {
        return "traces";
    }

    @Override
    public void install(PluginRegistry registry) {
        registry.around(RuntimeHook.MODEL_CALL, (call, next) -> traced(
                TraceRecord.MODEL_CALL, call.context(), String.valueOf(call.request().settings().model()),
                call, next, TracePlugin::usage));
        registry.around(RuntimeHook.TOOL_CALL, (invocation, next) -> traced(
                TraceRecord.TOOL_CALL, invocation.context(), invocation.call().toolName(),
                invocation, next, output -> Map.of("arguments", invocation.call().arguments(),
                        "outcome", output.getClass().getSimpleName())));
    }

    private <C, R> R traced(String kind, AgentContext context, String name, C work, Function<C, R> next,
                            Function<R, Map<String, Object>> details) {
        Instant startedAt = Instant.now();
        try {
            R result = next.apply(work);
            sink.record(record(kind, context, name, startedAt, details.apply(result), null));
            return result;
        } catch (RuntimeException e) {
            sink.record(record(kind, context, name, startedAt, Map.of(), String.valueOf(e.getMessage())));
            throw e;
        }
    }

    private static TraceRecord record(String kind, AgentContext context, String name, Instant startedAt,
                                      Map<String, Object> details, String error) {
        return new TraceRecord(kind,
                new TraceSubject(context.session().id(), context.turnId(), context.agent().name()), name,
                startedAt, Duration.between(startedAt, Instant.now()), details, error);
    }

    private static Map<String, Object> usage(LlmAnswer answer) {
        return Map.of("inputTokens", answer.usage().inputTokens(), "outputTokens", answer.usage().outputTokens());
    }
}
