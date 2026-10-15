package ai.mindconnect.agent.tools.workflow.step;

import ai.mindconnect.workflow.execution.WorkflowConfigurer;
import ai.mindconnect.workflow.execution.WorkflowContextFactory;

/**
 * Registers the agent-call step so it runs wherever this module is on the
 * classpath — discovered by {@code SpiWorkflowContextFactory} through the
 * {@code META-INF/services} file, like the script and form-step modules.
 */
public class AgentCallConfigurer implements WorkflowConfigurer {

    @Override
    public void configure(WorkflowContextFactory factory) {
        factory.getStepInstanceFactory().register(AgentCallData.class, AgentCallStep::new);
    }
}
