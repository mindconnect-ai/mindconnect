package ai.mindconnect.workflow.scripting;

import ai.mindconnect.workflow.execution.CodeStepException;

import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;

/**
 * Locates a JSR-223 {@link ScriptEngine} by language name.
 *
 * <p>Override this in tests or custom deployments to provide a mock engine or
 * to pre-warm engines for languages with high startup cost.
 */
public class ScriptEngineProvider {

    private final ScriptEngineManager manager = new ScriptEngineManager();

    /**
     * Returns an engine for the given language/engine name.
     *
     * @throws CodeStepException if no engine is registered for {@code language}
     */
    public ScriptEngine getEngine(String language) {
        ScriptEngine engine = manager.getEngineByName(language);
        if (engine == null) {
            engine = manager.getEngineByExtension(language);
        }
        if (engine == null) {
            engine = manager.getEngineByMimeType(language);
        }
        if (engine == null) {
            throw new CodeStepException(language, "",
                    new IllegalArgumentException(
                            "No JSR-223 ScriptEngine found for language '" + language + "'. "
                            + "Make sure the engine jar is on the classpath."));
        }
        return engine;
    }
}
