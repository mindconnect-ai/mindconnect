package ai.mindconnect.agent.tools.workflow.step;

import ai.mindconnect.workflow.execution.WorkflowConfigurer;
import ai.mindconnect.workflow.execution.WorkflowContextFactory;

/**
 * Registers the tool-call step — discovered by {@code SpiWorkflowContextFactory}
 * through the {@code META-INF/services} file, like {@link AgentCallConfigurer}.
 */
public class ToolCallConfigurer implements WorkflowConfigurer {

    @Override
    public void configure(WorkflowContextFactory factory) {
        factory.getStepInstanceFactory().register(ToolCallData.class, ToolCallStep::new);
    }
}
