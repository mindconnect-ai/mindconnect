package ai.mindconnect.workflow.step.form;

import ai.mindconnect.workflow.execution.WorkflowConfigurer;
import ai.mindconnect.workflow.execution.WorkflowContextFactory;

/**
 * Registers the form step so it runs wherever this module is on the classpath.
 *
 * <p>Discovered by {@code SpiWorkflowContextFactory} through the {@code
 * META-INF/services} file next to this class — the same auto-wiring the script
 * modules use. No host app has to know the step exists to run a workflow that
 * uses it.
 */
public class FormStepConfigurer implements WorkflowConfigurer {

    @Override
    public void configure(WorkflowContextFactory factory) {
        factory.getStepInstanceFactory().register(FormStepData.class, FormStep::new);
    }
}
