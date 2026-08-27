package ai.mindconnect.workflow.domain;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Invokes another workflow by name and maps variables into its input scope.
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class CallWorkflowData extends BaseStepContainerData {

    /** Name / id of the workflow to invoke (looked up in the registry). */
    private String workflow;

    /**
     * Parameter mappings: key = target param name inside the called workflow,
     * value = variable name or expression in the current scope.
     */
    private Map<String, String> assignParams = new LinkedHashMap<>();

    public void addAssignParam(String key, String value) {
        assignParams.put(key, value);
    }
}
