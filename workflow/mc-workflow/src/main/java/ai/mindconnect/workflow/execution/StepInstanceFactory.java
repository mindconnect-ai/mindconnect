package ai.mindconnect.workflow.execution;

import ai.mindconnect.workflow.domain.*;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Maps {@link StepData} types to their {@link StepInstance} implementations.
 *
 * <p>All built-in mappings are registered in the constructor. Custom step types
 * are registered via {@link #register(Class, Supplier)} — no reflection, no
 * coupling between domain and execution packages.
 *
 * <p>Example custom registration:
 * <pre>{@code
 * factory.register(SendEmailData.class, SendEmailStep::new);
 * }</pre>
 */
public class StepInstanceFactory {

    private final Map<Class<? extends StepData>, Supplier<? extends StepInstance<?>>> registry =
            new LinkedHashMap<>();

    public StepInstanceFactory() {
        registerBuiltIns();
    }

    // -----------------------------------------------------------------------
    // Extension point
    // -----------------------------------------------------------------------

    /**
     * Register a custom mapping. The {@code factory} supplier is called each
     * time a new step instance is needed — it must return a fresh object.
     */
    public <D extends StepData> void register(
            Class<D> dataClass,
            Supplier<? extends StepInstance<D>> factory) {
        registry.put(dataClass, factory);
    }

    // -----------------------------------------------------------------------
    // Instantiation
    // -----------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    public <D extends StepData> StepInstance<D> instantiate(D stepData) {
        Supplier<? extends StepInstance<?>> factory = registry.get(stepData.getClass());
        if (factory == null) {
            throw new IllegalArgumentException(
                    "No StepInstance registered for data type: " + stepData.getClass().getName()
                            + ". Register it via StepInstanceFactory.register().");
        }
        return (StepInstance<D>) factory.get();
    }

    // -----------------------------------------------------------------------
    // Built-in registrations — imported lazily to keep this class readable
    // -----------------------------------------------------------------------

    private void registerBuiltIns() {
        register(WorkflowData.class, WorkflowInstance::new);
        register(BlockData.class, BlockStep::new);
        register(ForEachData.class, ForEachStep::new);
        register(IfData.class, IfStep::new);
        register(HaltData.class, HaltStep::new);
        register(JumpToData.class, JumpToStep::new);
        register(AssignVariablesData.class, AssignVariablesStep::new);
        register(CallWorkflowData.class, CallWorkflowStep::new);
        register(CodeData.class, CodeStep::new);
        register(HttpCallData.class, HttpCallStep::new);
    }
}
