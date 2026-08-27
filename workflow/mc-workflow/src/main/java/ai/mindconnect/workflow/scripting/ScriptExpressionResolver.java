package ai.mindconnect.workflow.scripting;

import ai.mindconnect.workflow.execution.CodeStep;
import ai.mindconnect.workflow.execution.CodeStepException;
import ai.mindconnect.workflow.execution.ExpressionResolver;
import ai.mindconnect.workflow.execution.VariableScope;

import java.util.Set;

/**
 * An {@link ExpressionResolver} that dispatches to JSR-223 script engines
 * using a {@code lang:expression} prefix convention.
 *
 * <p>Examples:
 * <pre>
 *   javascript: x + 1
 *   groovy:     items.sum()
 *   beanshell:  amount > 1000 ? "premium" : "basic"
 *   python:     len(items) > 0
 * </pre>
 *
 * <p>The shared {@link ScriptExecutor} is used for engine lookup — the same
 * registry that {@link CodeStep} uses, so language modules only need to register
 * their engine once.
 *
 * <p>Variables are exposed via a live {@link VariableScopeBinding} so script
 * side-effects (variable assignments) are written back into the workflow scope.
 */
public class ScriptExpressionResolver implements ExpressionResolver {

    private final ScriptExecutor scriptExecutor;

    public ScriptExpressionResolver(ScriptExecutor scriptExecutor) {
        this.scriptExecutor = scriptExecutor;
    }

    // -----------------------------------------------------------------------
    // ExpressionResolver
    // -----------------------------------------------------------------------

    @Override
    public boolean containsExpression(Object value) {
        if (!(value instanceof String str)) return false;
        int colon = str.indexOf(':');
        if (colon <= 0) return false;
        String prefix = str.substring(0, colon).trim();
        return scriptExecutor.getRegisteredLanguages().contains(prefix);
    }

    @Override
    public String extractExpression(String expression) {
        int colon = expression.indexOf(':');
        return colon >= 0 ? expression.substring(colon + 1).trim() : expression;
    }

    @Override
    public Object eval(VariableScope variableScope, String expressionString) {
        String lang       = extractLang(expressionString);
        String expression = extractExpression(expressionString);

        VariableScopeBinding binding = new VariableScopeBinding(variableScope);
        try {
            return scriptExecutor.eval(lang, expression, binding);
        } catch (CodeStepException ex) {
            throw new RuntimeException(
                    "Script expression evaluation failed [" + lang + "]: " + ex.getMessage(), ex);
        }
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    public String extractLang(String expression) {
        int colon = expression.indexOf(':');
        return colon >= 0 ? expression.substring(0, colon).trim() : null;
    }

    public Set<String> getRegisteredLanguages() {
        return scriptExecutor.getRegisteredLanguages();
    }
}
