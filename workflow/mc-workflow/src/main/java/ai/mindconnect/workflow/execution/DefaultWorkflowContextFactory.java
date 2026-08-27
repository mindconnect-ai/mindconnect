package ai.mindconnect.workflow.execution;

import ai.mindconnect.script.mini.MiniScriptEngineFactory;
import ai.mindconnect.workflow.scripting.ScriptExecutor;
import ai.mindconnect.workflow.scripting.ScriptExpressionResolver;
import ai.mindconnect.workflow.util.StringVariableReplacer;

import javax.script.ScriptEngineFactory;
import javax.script.ScriptEngineManager;

/**
 * A {@link WorkflowContextFactory} with all members pre-initialised to
 * sensible defaults.
 *
 * <ul>
 *   <li>{@link StringVariableReplacer} — pre-loaded with {@code MapPathAccessor},
 *       {@code BeanPathAccessor} and {@code RecordPathAccessor}</li>
 *   <li>{@link StepInstanceFactory} — all built-in step types registered</li>
 * </ul>
 *
 * All other fields ({@code expressionResolver}, {@code scriptExecutor},
 * {@code jsonMapper}, {@code workflowDefinitionRegistry}, {@code baseDir})
 * start as {@code null} and must be set explicitly when needed.
 *
 * <p>Use this class as the starting point whenever creating a factory from
 * scratch — in tests, in application code, and in configurer chains:
 * <pre>{@code
 * WorkflowContextFactory factory = new DefaultWorkflowContextFactory();
 * new JavaScriptWorkflowConfigurer().configure(factory);
 * new SpElWorkflowConfigurer().configure(factory);
 * WorkflowExecutorService service = new WorkflowExecutorService(factory);
 * }</pre>
 */
public class DefaultWorkflowContextFactory extends WorkflowContextFactory {

    public DefaultWorkflowContextFactory() {
        setStringVariableReplacer(new StringVariableReplacer());
        setStepInstanceFactory(new StepInstanceFactory());
        setScriptExecutor(new ScriptExecutor());

        // Always register the built-in MiniScript engine
        getScriptExecutor().register("mini", new MiniScriptEngineFactory());

        // Auto-register all JSR-223 engines discovered on the classpath via SPI.
        // Each engine is registered under its primary name so it appears in
        // ScriptExecutor.getRegisteredLanguages() and therefore in the UI dropdown.
        ScriptEngineManager mgr = new ScriptEngineManager();
        for (ScriptEngineFactory factory : mgr.getEngineFactories()) {
            String primary = factory.getLanguageName().toLowerCase();
            getScriptExecutor().register(primary, factory);
        }
        CompositeExpressionResolver resolver = new CompositeExpressionResolver(getStringVariableReplacer());
        resolver.add(new ScriptExpressionResolver(getScriptExecutor()));
        setExpressionResolver(resolver);
    }
}
