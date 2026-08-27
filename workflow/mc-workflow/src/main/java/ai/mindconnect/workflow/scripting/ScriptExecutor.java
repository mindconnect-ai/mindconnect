package ai.mindconnect.workflow.scripting;

import ai.mindconnect.workflow.execution.CodeStep;
import ai.mindconnect.workflow.execution.CodeStepException;
import ai.mindconnect.workflow.execution.WorkflowContextFactory;

import javax.script.*;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Central registry and execution engine for JSR-223 scripting.
 *
 * <p>Language modules register their {@link ScriptEngineFactory} instances here
 * via {@link #register(String, ScriptEngineFactory)}.  Both {@link CodeStep} and
 * {@link ScriptExpressionResolver} share the same {@code ScriptExecutor} instance
 * obtained from the {@link WorkflowContextFactory},
 * so the engine registry is configured once and reused across all step executions
 * and inline expressions.
 *
 * <p>Multiple aliases for the same engine are supported:
 * <pre>{@code
 * scriptExecutor.register("javascript", graalFactory);
 * scriptExecutor.register("js",         graalFactory);
 * }</pre>
 *
 * <p>If no factory is registered for a language, the standard
 * {@link ScriptEngineManager} classpath discovery is used as a fallback.
 */
public class ScriptExecutor {

    private final Map<String, ScriptEngineFactory> factoryByName = new LinkedHashMap<>();
    private final Map<String, ScriptEngine> engineCache = new LinkedHashMap<>();
    private final ScriptEngineManager fallbackManager = new ScriptEngineManager();

    // -----------------------------------------------------------------------
    // Registration
    // -----------------------------------------------------------------------

    /**
     * Registers a {@link ScriptEngineFactory} under one or more alias names.
     *
     * @param name    the language name / alias (e.g. {@code "groovy"}, {@code "js"})
     * @param factory the factory that produces engines for this language
     */
    public void register(String name, ScriptEngineFactory factory) {
        factoryByName.put(name, factory);
    }

    /** Returns an unmodifiable view of all registered language names. */
    public Set<String> getRegisteredLanguages() {
        return Collections.unmodifiableSet(factoryByName.keySet());
    }

    // -----------------------------------------------------------------------
    // Engine resolution
    // -----------------------------------------------------------------------

    /**
     * Returns a {@link ScriptEngine} for {@code language}.
     * Tries registered factories first, then falls back to
     * {@link ScriptEngineManager} classpath discovery.
     *
     * @throws CodeStepException if no engine can be found
     */
    public ScriptEngine getEngine(String language) {
        return engineCache.computeIfAbsent(language, this::createEngine);
    }

    private ScriptEngine createEngine(String language) {
        ScriptEngineFactory factory = factoryByName.get(language);
        if (factory != null) {
            return factory.getScriptEngine();
        }

        ScriptEngine engine = fallbackManager.getEngineByName(language);
        if (engine == null) engine = fallbackManager.getEngineByExtension(language);
        if (engine == null) engine = fallbackManager.getEngineByMimeType(language);

        if (engine == null) {
            throw new CodeStepException(language, "",
                    new IllegalArgumentException(
                            "No JSR-223 ScriptEngine found for language '" + language + "'. "
                            + "Register an engine via ScriptExecutor.register() or add the "
                            + "engine jar to the classpath."));
        }
        return engine;
    }

    // -----------------------------------------------------------------------
    // Evaluation
    // -----------------------------------------------------------------------

    /**
     * Evaluates {@code expression} in the named scripting language using the
     * provided {@link Bindings} for variable access.
     *
     * @param language   the language name (e.g. {@code "groovy"})
     * @param expression the source expression or script
     * @param bindings   variable bindings available to the script
     * @return the result of the evaluation
     * @throws CodeStepException on engine-not-found or script errors
     */
    public Object eval(String language, String expression, Bindings bindings) {
        ScriptEngine engine = getEngine(language);
        engine.setBindings(bindings, ScriptContext.ENGINE_SCOPE);
        try {
            return engine.eval(expression);
        } catch (ScriptException ex) {
            throw new CodeStepException(language, expression, ex);
        } finally {
            engine.setBindings(engine.createBindings(), ScriptContext.ENGINE_SCOPE);
        }
    }
}
