package ai.mindconnect.workflow.execution;

import ai.mindconnect.workflow.util.StringVariableReplacer;

import java.util.*;

/**
 * An {@link ExpressionResolver} that delegates to multiple resolvers in order.
 *
 * <p>The first resolver that {@link #containsExpression reports} it can handle
 * the expression is asked to evaluate it.  This allows combining independent
 * resolvers — for example SpEL and JSR-223 script engines — without coupling
 * them to each other.
 *
 * <p>Usage:
 * <pre>
 *   ExpressionResolver resolver = new CompositeExpressionResolver(
 *       new SpElResolver(),
 *       new ScriptExpressionResolver()
 *   );
 * </pre>
 */
public class CompositeExpressionResolver implements ExpressionResolver {

    private final Set<ExpressionResolver> resolvers = new LinkedHashSet<>();
    private final StringVariableReplacer stringVariableReplacer;

    public CompositeExpressionResolver(StringVariableReplacer replacer, ExpressionResolver... resolvers) {
        this.stringVariableReplacer = replacer;
        this.resolvers.addAll(Arrays.asList(resolvers));
    }

    public CompositeExpressionResolver(StringVariableReplacer stringVariableReplacer, List<ExpressionResolver> resolvers) {
        this.stringVariableReplacer = stringVariableReplacer;
        this.resolvers.addAll(resolvers);
    }

    /**
     * Adds a resolver to the end of the chain.
     */
    public void add(ExpressionResolver resolver) {
        resolvers.add(resolver);
    }

    // -----------------------------------------------------------------------
    // ExpressionResolver
    // -----------------------------------------------------------------------

    @Override
    public boolean containsExpression(Object value) {
        if (value instanceof String expression) {
            if (expression.contains("${") && expression.contains("}")) {
                return true;
            }
        }
        return resolvers.stream().anyMatch(r -> r.containsExpression(value));
    }

    @Override
    public String extractExpression(String expression) {
        for (ExpressionResolver r : resolvers) {
            if (r.containsExpression(expression)) {
                return r.extractExpression(expression);
            }
        }
        return expression;
    }

    @Override
    public Object eval(VariableScope variableScope, String expressionString) {
        var resolvedExpression = stringVariableReplacer.replaceVars(expressionString, variableScope::getVariableValue);
        for (ExpressionResolver r : resolvers) {
            if (r.containsExpression(expressionString)) {
                return r.eval(variableScope, resolvedExpression);
            }
        }
        return resolvedExpression;
    }
}
