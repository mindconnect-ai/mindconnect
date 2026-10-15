package ai.mindconnect.agent.tools.workflow.step;

import ai.mindconnect.workflow.domain.BaseStepData;

/**
 * A workflow step that executes one registered agent tool and yields the
 * tool's text result as the step result ({@code assignResultToVar} works as
 * usual). Any tool the runtime's registry knows is callable — built-ins,
 * Gmail/MCP bundles, and workflow tools alike.
 *
 * <p>Arguments are given as a JSON object; the raw text is expression-resolved
 * first, so {@code ${var}} references inside it pick up workflow variables.
 *
 * <p>Type discriminator: {@code toolcall}.
 */
public class ToolCallData extends BaseStepData {

    /** Registry name of the tool to execute (e.g. {@code web_search}). */
    private String tool;

    /** JSON object with the tool arguments; expression-resolvable ({@code ${var}}). */
    private String arguments;

    /**
     * Alternative to {@link #arguments}: the arguments as a structure, each
     * string in it resolved on its own ({@code ${var}}, {@code mini:},
     * {@code json:}). A string that is exactly one {@code ${var}} becomes the
     * variable's value, list or map included. Nothing is spliced into JSON
     * text, so a value with quotes or line breaks arrives as it is.
     */
    private java.util.Map<String, Object> argumentValues;

    /**
     * Whether a tool result carrying the {@code Error:} convention fails the
     * step (and with it the workflow). Default {@code true} — a failed tool
     * must never masquerade as a successful step (a workflow that "succeeds"
     * while its tool errored is exactly how ingestion once reported success
     * with zero chunks). Set {@code false} for steps that want to inspect
     * the error text themselves.
     */
    private Boolean failOnError;

    public String getTool() {
        return tool;
    }

    public void setTool(String tool) {
        this.tool = tool;
    }

    public String getArguments() {
        return arguments;
    }

    public void setArguments(String arguments) {
        this.arguments = arguments;
    }

    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    public java.util.Map<String, Object> getArgumentValues() {
        return argumentValues;
    }

    public void setArgumentValues(java.util.Map<String, Object> argumentValues) {
        this.argumentValues = argumentValues;
    }

    public Boolean getFailOnError() {
        return failOnError;
    }

    public void setFailOnError(Boolean failOnError) {
        this.failOnError = failOnError;
    }

    /** {@code null} (legacy step data) means the safe default: fail. */
    public boolean effectiveFailOnError() {
        return !Boolean.FALSE.equals(failOnError);
    }
}
