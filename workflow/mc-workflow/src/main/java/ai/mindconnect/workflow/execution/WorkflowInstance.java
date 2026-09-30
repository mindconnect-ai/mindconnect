package ai.mindconnect.workflow.execution;

import ai.mindconnect.workflow.domain.WorkflowData;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.Map;

/** Top-level execution instance for a {@link WorkflowData} definition. */
@Data
@EqualsAndHashCode(callSuper = true)
public class WorkflowInstance extends BaseStepContainerInstance<WorkflowData> {

    /**
     * Sets an input of the run. Inputs are data — they come from whoever starts
     * or resumes the run (an HTTP caller, a model's tool arguments, a human's
     * resume form) — so a value is stored as given, never evaluated: a string
     * like {@code mini: …} stays that string instead of running as a script.
     */
    public WorkflowInstance assignParam(String key, Object value) {
        getVariableScope().assignValue(key, value, null);
        return this;
    }

    public void assignParams(Map<String, Object> params) {
        if (params != null) {
            params.forEach(this::assignParam);
        }
    }
}
