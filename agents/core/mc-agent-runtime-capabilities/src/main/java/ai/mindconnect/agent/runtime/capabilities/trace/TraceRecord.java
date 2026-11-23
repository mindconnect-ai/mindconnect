package ai.mindconnect.agent.runtime.capabilities.trace;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;


/**
 * One thing an agent did, as someone will want to read it later: who, in
 * which turn, what, how long, and whether it failed.
 *
 * @param kind    {@link #MODEL_CALL} or {@link #TOOL_CALL}
 * @param name    the model or the tool
 * @param details what belongs to the kind: token usage, arguments
 * @param error   why it failed, or {@code null}
 */
public record TraceRecord(
        String kind,
        TraceSubject subject,
        String name,
        Instant startedAt,
        Duration duration,
        Map<String, Object> details,
        String error
) {

    public static final String MODEL_CALL = "model_call";
    public static final String TOOL_CALL = "tool_call";

    public TraceRecord {
        details = Map.copyOf(details);
    }
}
