package ai.mindconnect.workflow.code.jython;

import ai.mindconnect.workflow.execution.WorkflowConfigurer;
import ai.mindconnect.workflow.execution.WorkflowContextFactory;
import ai.mindconnect.workflow.scripting.ScriptExecutor;
import org.python.jsr223.PyScriptEngineFactory;

/**
 * Registers the Jython (Python 2.7) engine with the shared {@link ScriptExecutor}.
 *
 * <p>After this configurer runs, {@link ai.mindconnect.workflow.execution.CodeStep}
 * and {@link ai.mindconnect.workflow.scripting.ScriptExpressionResolver} resolve
 * Python/Jython code using the aliases {@code "python"} and {@code "jython"}.
 *
 * <p>Example step data:
 * <pre>{@code
 * CodeData step = new CodeData();
 * step.setLanguage("python");
 * step.setCode("[x.upper() for x in items]");
 * }</pre>
 *
 * <p>Example inline expression:
 * <pre>{@code
 * assignVariables.add(new VariableAssignment("result", "python: x * 2"));
 * }</pre>
 *
 * <p>Note: uses the thread context classloader to ensure Jython's
 * {@code META-INF/services} entry is visible in all deployment environments.
 */
public final class JythonWorkflowConfigurer implements WorkflowConfigurer {

    /** No-arg constructor required by {@link java.util.ServiceLoader}. */
    public JythonWorkflowConfigurer() {}

    @Override
    public void configure(WorkflowContextFactory factory) {
        ScriptExecutor executor = getOrCreate(factory);
        // Use thread-context classloader so Jython's service entry is visible
        // in shaded-jar and OSGi deployments
        PyScriptEngineFactory pyFactory = new PyScriptEngineFactory();
        executor.register("python", pyFactory);
        executor.register("jython", pyFactory);
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
