package ai.mindconnect.workflow.execution;

import ai.mindconnect.workflow.domain.CodeData;
import ai.mindconnect.workflow.scripting.ScriptEngineProvider;
import ai.mindconnect.workflow.scripting.ScriptExecutor;

import javax.script.Bindings;
import javax.script.ScriptEngine;
import java.io.Writer;
import java.util.Map;

/**
 * Executes a code snippet in a JSR-223 scripting engine.
 *
 * <p>The engine is resolved via the {@link ScriptExecutor} held by the
 * {@link WorkflowContextFactory}, which is populated by language module
 * configurers at startup.  All language modules share the same
 * {@code ScriptExecutor} instance, so engine registration happens once.
 *
 * <p>When no {@code ScriptExecutor} is present in the context (e.g. in tests
 * using a minimal setup), the step falls back to {@link #buildFallbackProvider()}
 * which uses the standard {@link javax.script.ScriptEngineManager} classpath
 * discovery.
 *
 * @param <T> concrete {@link CodeData} subtype this step is configured by
 */
public class CodeStep<T extends CodeData> extends BaseStepInstance<T> {

    // -----------------------------------------------------------------------
    // Engine resolution
    // -----------------------------------------------------------------------

    /**
     * Returns the {@link ScriptExecutor} from the workflow context, or
     * {@code null} when none is configured.
     */
    protected ScriptExecutor getScriptExecutor() {
        if (getWorkflowContext() == null) return null;
        Object raw = getWorkflowContext().getScriptExecutor();
        return raw instanceof ScriptExecutor se ? se : null;
    }

    /**
     * Fallback provider used when no {@link ScriptExecutor} is in the context.
     * Override in tests to supply a mock engine.
     */
    protected ScriptEngineProvider buildFallbackProvider() {
        return new ScriptEngineProvider();
    }

    private ScriptEngine resolveEngine(String language) {
        ScriptExecutor executor = getScriptExecutor();
        if (executor != null) {
            return executor.getEngine(language);
        }
        return buildFallbackProvider().getEngine(language);
    }

    // -----------------------------------------------------------------------
    // execute
    // -----------------------------------------------------------------------

    @Override
    public void execute() throws Exception {
        CodeData cfg = getConfig();
        String language = cfg.getLanguage() != null ? cfg.getLanguage() : "javascript";
        String code     = cfg.getCode();

        if (code == null || code.isBlank()) {
            logDebug("code is empty — skipping");
            setResult(null);
            return;
        }

        logDebug("executing %s code (%d chars)", language, code.length());

        String script = cfg.isWrapInFunction()
                ? "(function(){ return " + code + "; })()"
                : code;

        Object result;
        Bindings bindings;
        try {
            ScriptEngine engine = resolveEngine(language);
            // The engine's own bindings implementation, fresh per execution:
            // GraalJS only reflects script globals back into bindings it
            // created itself (a SimpleBindings never sees them, which silently
            // broke exportVariables), and a fresh instance keeps executions
            // isolated even though the engine is cached per language.
            bindings = engine.createBindings();
            if (cfg.isInjectVariables()) {
                injectVariables(bindings);
            }
            result = engine.eval(script, bindings);
        } catch (CodeStepException ex) {
            throw ex; // already wrapped
        } catch (Exception ex) {
            throw new CodeStepException(language, code, ex);
        }

        logDebug("code result: %s", result);

        if (cfg.isExportVariables()) {
            exportVariables(bindings);
        }

        setResult(result);
    }

    // -----------------------------------------------------------------------
    // Variable injection / export
    // -----------------------------------------------------------------------

    private void injectVariables(Bindings bindings) {
        // Skip null keys defensively: an unset ForEach indexVar (or any
        // anonymous scope entry) is stored under a null name, and strict
        // Bindings implementations (GraalJS) reject null keys.
        for (Map.Entry<String, Object> entry : getVariableScope().getVariableValueMap().entrySet()) {
            if (entry.getKey() != null && !entry.getKey().isBlank()) {
                bindings.put(entry.getKey(), entry.getValue());
            }
        }
    }

    private void exportVariables(Bindings bindings) {
        Map<String, Object> before = getVariableScope().getVariableValueMap();
        for (Map.Entry<String, Object> entry : bindings.entrySet()) {
            String key   = entry.getKey();
            Object value = entry.getValue();
            boolean isNewOrChanged = !before.containsKey(key)
                    || !objectEquals(before.get(key), value);
            if (isNewOrChanged) {
                getVariableScope().assignValueToParentScope(key, value, null);
                logDebug("exported variable: %s = %s", key, value);
            }
        }
    }

    private boolean objectEquals(Object a, Object b) {
        if (a == b) return true;
        if (a == null || b == null) return false;
        return a.equals(b);
    }
}
