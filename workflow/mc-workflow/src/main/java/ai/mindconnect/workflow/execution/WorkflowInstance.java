package ai.mindconnect.workflow.execution;

import ai.mindconnect.workflow.domain.WorkflowData;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.Map;

/** Top-level execution instance for a {@link WorkflowData} definition. */
@Data
@EqualsAndHashCode(callSuper = true)
public class WorkflowInstance extends BaseStepContainerInstance<WorkflowData> {

    public WorkflowInstance assignParam(String key, Object value) {
        getVariableScope().assignValue(key, value, getExpressionResolver());
        return this;
    }

    public void assignParams(Map<String, Object> params) {
        if (params != null) {
            params.forEach(this::assignParam);
        }
    }
}
