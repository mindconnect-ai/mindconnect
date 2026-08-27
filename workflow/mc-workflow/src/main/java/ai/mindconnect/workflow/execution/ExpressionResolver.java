package ai.mindconnect.workflow.execution;

/**
 * Strategy for evaluating expressions against the current variable scope.
 * Implementations can support SpEL, Groovy, plain ${var} substitution, etc.
 * The engine has no hard dependency on any specific expression language.
 */
public interface ExpressionResolver {

    /**
     * Evaluate {@code expression} with variables visible via {@code scope}.
     *
     * @return the result of the expression — type depends on the expression
     */
    Object eval(VariableScope variableScope, String expression);

    /**
     * Returns true when {@code value} contains an expression that this resolver can handle.
     * Used to distinguish plain strings from expressions before calling {@link #eval}.
     */
    boolean containsExpression(Object value);

    /**
     * Strips the resolver-specific prefix/markers from {@code expression}
     * and returns the raw expression string.
     */
    String extractExpression(String expression);
}
