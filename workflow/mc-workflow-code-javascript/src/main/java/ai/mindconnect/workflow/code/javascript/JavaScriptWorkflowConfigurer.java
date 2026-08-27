package ai.mindconnect.workflow.code.javascript;

import ai.mindconnect.workflow.execution.WorkflowConfigurer;
import ai.mindconnect.workflow.execution.WorkflowContextFactory;
import ai.mindconnect.workflow.scripting.ScriptExecutor;
import com.oracle.truffle.js.scriptengine.GraalJSEngineFactory;

/**
 * Registers the GraalJS (JavaScript) engine with the shared {@link ScriptExecutor}.
 *
 * <p>After this configurer runs, {@link ai.mindconnect.workflow.code.CodeStep}
 * and {@link ai.mindconnect.workflow.code.ScriptExpressionResolver} resolve
 * JavaScript code using any of the aliases: {@code "javascript"}, {@code "js"},
 * {@code "graal.js"}.
 *
 * <p>Example step data:
 * <pre>{@code
 * CodeData step = new CodeData();
 * step.setLanguage("javascript");
 * step.setCode("price * quantity");
 * }</pre>
 *
 * <p>Example inline expression:
 * <pre>{@code
 * assignVariables.add(new VariableAssignment("total", "javascript: price * quantity"));
 * }</pre>
 */
public final class JavaScriptWorkflowConfigurer implements WorkflowConfigurer {

    /** No-arg constructor required by {@link java.util.ServiceLoader}. */
    public JavaScriptWorkflowConfigurer() {}

    @Override
    public void configure(WorkflowContextFactory factory) {
        ScriptExecutor executor = getOrCreate(factory);
        GraalJSEngineFactory graalFactory = new GraalJSEngineFactory();
        executor.register("javascript", graalFactory);
        executor.register("js",         graalFactory);
        executor.register("graal.js",   graalFactory);
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
