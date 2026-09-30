package ai.mindconnect.workflow.admin.run;

import ai.mindconnect.schema.Schema;
import ai.mindconnect.workflow.admin.ui.ParamCoercion;
import ai.mindconnect.workflow.domain.WorkflowData;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A workflow's inputs as a caller outside the admin screen hands them over —
 * the scheduler, a quick action in the mail composer — made ready for the
 * engine, which takes its params as they come: no defaults, no check.
 *
 * <p>Each value its param types is coerced ({@link ParamCoercion}); a param
 * left out gets its declared default; and a required param that is still
 * missing is named before the run starts, rather than failing somewhere in
 * the middle of it.
 */
public final class WorkflowInputs {

    private WorkflowInputs() {}

    /**
     * @param workflow     the workflow to be run
     * @param submitted    what the caller has; may be null
     * @param declaredOnly keep only the params the workflow declares — for a
     *                     caller that offers a fixed set of values (the mail's
     *                     text, subject, recipients) to every workflow alike
     * @return the params to run with
     * @throws IllegalArgumentException naming the required params that have no value
     */
    public static Map<String, Object> prepare(WorkflowData workflow, Map<String, Object> submitted,
                                              boolean declaredOnly) {
        Schema params = workflow.getParams();
        Map<String, Schema> declared = params == null || params.getProperties() == null
                ? Map.of() : params.getProperties();
        Map<String, Object> in = new LinkedHashMap<>();
        if (submitted != null) {
            submitted.forEach((name, value) -> {
                if (value != null && (!declaredOnly || declared.containsKey(name))) in.put(name, value);
            });
        }
        Map<String, Object> out = ParamCoercion.coerce(params, in);
        declared.forEach((name, schema) -> {
            if (!out.containsKey(name) && schema != null && schema.getDefaultValue() != null) {
                out.put(name, schema.getDefaultValue());
            }
        });
        List<String> missing = new ArrayList<>();
        if (params != null && params.getRequired() != null) {
            for (String name : params.getRequired()) {
                Object value = out.get(name);
                if (value == null || value instanceof String s && s.isBlank()) missing.add(name);
            }
        }
        if (!missing.isEmpty()) {
            String what = workflow.getName() != null ? workflow.getName() : "The workflow";
            throw new IllegalArgumentException(what + " needs " + (missing.size() == 1 ? "the input " : "the inputs ")
                    + String.join(", ", missing) + ".");
        }
        return out;
    }
}
