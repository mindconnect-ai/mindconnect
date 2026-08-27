package ai.mindconnect.workflow.code.beanshell;

import ai.mindconnect.workflow.execution.WorkflowConfigurer;
import ai.mindconnect.workflow.execution.WorkflowContextFactory;
import ai.mindconnect.workflow.scripting.ScriptExecutor;
import bsh.engine.BshScriptEngineFactory;

/**
 * Registers the BeanShell engine with the shared {@link ScriptExecutor}.
 *
 * <p>After this configurer runs, {@link ai.mindconnect.workflow.code.CodeStep}
 * and {@link ai.mindconnect.workflow.code.ScriptExpressionResolver} resolve
 * BeanShell code using the aliases {@code "beanshell"} and {@code "bsh"}.
 *
 * <p>Example step data:
 * <pre>{@code
 * CodeData step = new CodeData();
 * step.setLanguage("beanshell");
 * step.setCode("int total = price * qty; return total;");
 * }</pre>
 *
 * <p>Example inline expression:
 * <pre>{@code
 * ifData.setCondition("beanshell: amount > 1000");
 * }</pre>
 */
public final class BeanShellWorkflowConfigurer implements WorkflowConfigurer {

    /** No-arg constructor required by {@link java.util.ServiceLoader}. */
    public BeanShellWorkflowConfigurer() {}

    @Override
    public void configure(WorkflowContextFactory factory) {
        ScriptExecutor executor = getOrCreate(factory);
        BshScriptEngineFactory bshFactory = new BshScriptEngineFactory();
        executor.register("beanshell", bshFactory);
        executor.register("bsh",       bshFactory);
    }

    static ScriptExecutor getOrCreate(WorkflowContextFactory factory) {
        if (factory.getScriptExecutor() instanceof ScriptExecutor se) {
            return se;
        }
        ScriptExecutor se = new ScriptExecutor();
        factory.setScriptExecutor(se);
        return se;
    }
}
