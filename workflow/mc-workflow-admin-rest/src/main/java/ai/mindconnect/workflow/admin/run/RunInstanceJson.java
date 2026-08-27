package ai.mindconnect.workflow.admin.run;

import ai.mindconnect.workflow.execution.StepExecutionInfo;
import ai.mindconnect.workflow.execution.StepInstance;
import ai.mindconnect.workflow.jackson.WorkflowObjectMapperFactory;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Renders a {@link StepInstance} as JSON for the run view.
 *
 * <p>Deliberately <b>not</b> a serialization of the live object. A step
 * instance is a mix of three things: its definition ({@code config}), the
 * runtime collaborators it borrowed ({@code workflowContext} — script engines,
 * expression resolvers, event listeners, an output {@code Writer}), and the
 * state it actually produced. Only the last two of those mean anything to a
 * reader, and the middle one cannot be written to JSON at all. So this builds
 * an explicit snapshot of the fields worth showing rather than handing the
 * object graph to Jackson and hoping.
 */
public final class RunInstanceJson {

    /** Long values (an {@code env} map, a fat HTTP body) are cut for display. */
    private static final int MAX_VALUE_CHARS = 2_000;

    private static final ObjectMapper MAPPER = WorkflowObjectMapperFactory.create();

    private RunInstanceJson() {}

    /** The instance's own state, as pretty JSON. Never throws. */
    public static String of(StepInstance<?> instance, Exception error) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("class", instance.getClass().getName());
        out.put("type", instance.getConfig() == null ? null : instance.getConfig().getType());
        out.put("name", instance.getConfig() == null ? null : instance.getConfig().getName());
        out.put("state", instance.getState() == null ? null : instance.getState().name());

        StepExecutionInfo info = instance.getStepExecutionInfo();
        if (info != null) {
            Map<String, Object> exec = new LinkedHashMap<>();
            exec.put("positionInStepContainer", info.getPositionInStepContainer());
            exec.put("startTime", info.getStartTime());
            exec.put("endTime", info.getEndTime());
            exec.put("durationInMs", info.getDurationInMs());
            exec.put("errorMessage", info.getErrorMessage());
            exec.put("logCount", info.getLogs() == null ? 0 : info.getLogs().size());
            out.put("execution", exec);
        }

        out.put("result", display(instance.getResult()));
        if (error != null) {
            out.put("error", error.getMessage());
        }
        // The definition this instance was built from — safe, it is what the
        // store already holds on disk.
        out.put("config", instance.getConfig());

        try {
            return MAPPER.writeValueAsString(out);
        } catch (Exception ex) {
            return "{ \"error\": \"Cannot render step instance: " + ex.getMessage() + "\" }";
        }
    }

    /**
     * A value as the run view shows it. Anything can end up in a workflow
     * variable — a script object, a parsed HTTP body, a stream — so this never
     * trusts the value to be printable and never lets it be unbounded.
     */
    public static String display(Object value) {
        if (value == null) return "null";
        String text;
        try {
            text = String.valueOf(value);
        } catch (Exception ex) {
            return "<" + value.getClass().getSimpleName() + ": toString() failed>";
        }
        if (text.length() > MAX_VALUE_CHARS) {
            return text.substring(0, MAX_VALUE_CHARS)
                    + "… (" + text.length() + " chars, truncated)";
        }
        return text;
    }
}
