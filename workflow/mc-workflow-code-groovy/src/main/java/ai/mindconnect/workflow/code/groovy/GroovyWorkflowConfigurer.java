package ai.mindconnect.workflow.code.groovy;

import ai.mindconnect.workflow.execution.WorkflowConfigurer;
import ai.mindconnect.workflow.execution.WorkflowContextFactory;
import ai.mindconnect.workflow.scripting.ScriptExecutor;
import org.codehaus.groovy.jsr223.GroovyScriptEngineFactory;

/**
 * Registers the Groovy engine with the shared {@link ScriptExecutor}.
 *
 * <p>After this configurer runs, {@link ai.mindconnect.workflow.code.CodeStep}
 * and {@link ai.mindconnect.workflow.code.ScriptExpressionResolver} resolve
 * Groovy code using the alias {@code "groovy"}.
 *
 * <p>Example step data:
 * <pre>{@code
 * CodeData step = new CodeData();
 * step.setLanguage("groovy");
 * step.setCode("price * quantity * 0.9");
 * }</pre>
 *
 * <p>Example inline expression:
 * <pre>{@code
 * assignVariables.add(new VariableAssignment("label", "groovy: amount > 1000 ? 'premium' : 'standard'"));
 * }</pre>
 */
public final class GroovyWorkflowConfigurer implements WorkflowConfigurer {

    /**
     * No-arg constructor required by {@link java.util.ServiceLoader}.
     */
    public GroovyWorkflowConfigurer() {
    }

    @Override
    public void configure(WorkflowContextFactory factory) {
        ScriptExecutor executor = getOrCreate(factory);
        GroovyScriptEngineFactory groovyFactory = new GroovyScriptEngineFactory();
        executor.register("groovy", groovyFactory);
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
