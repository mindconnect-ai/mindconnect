package ai.mindconnect.workflow.jackson;

import ai.mindconnect.workflow.execution.ExpressionResolver;
import ai.mindconnect.workflow.execution.VariableScope;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * An {@link ExpressionResolver} that parses a JSON string literal.
 *
 * <p>Expressions are recognised by the {@code json:} prefix:
 * <pre>
 *   json: {"name":"David","age":40}    → LinkedHashMap
 *   json: [1, 2, 3]                    → ArrayList
 *   json: "hello"                      → String
 *   json: 42                           → Integer / Long
 *   json: true                         → Boolean
 * </pre>
 *
 * <p>This is useful for supplying structured objects as workflow input
 * parameters from the UI or command-line without needing a scripting engine.
 * For example, passing an input parameter value of
 * {@code json: {"items":["a","b"]}} will parse and inject the map directly
 * into the workflow variable scope.
 *
 * <p>Variable substitution ({@code ${var}}) is applied to the JSON string
 * <em>before</em> parsing so dynamic values can be embedded:
 * <pre>
 *   json: {"user":"${username}"}
 * </pre>
 */
public class JsonExpressionResolver implements ExpressionResolver {

    static final String PREFIX = "json:";

    private final ObjectMapper mapper;

    public JsonExpressionResolver(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    // -----------------------------------------------------------------------
    // ExpressionResolver
    // -----------------------------------------------------------------------

    @Override
    public boolean containsExpression(Object value) {
        if (!(value instanceof String s)) return false;
        return s.stripLeading().startsWith(PREFIX);
    }

    @Override
    public String extractExpression(String expression) {
        int colon = expression.indexOf(':');
        return colon >= 0 ? expression.substring(colon + 1).trim() : expression;
    }

    @Override
    public Object eval(VariableScope variableScope, String expressionString) {
        String json = extractExpression(expressionString);

        // Substitute ${var} placeholders before parsing so callers can embed
        // dynamic values, e.g.  json: {"name":"${firstName}"}
        if (variableScope != null) {
            json = replaceVars(json, variableScope);
        }

        try {
            return mapper.readValue(json, Object.class);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(
                    "json: expression is not valid JSON: " + e.getOriginalMessage()
                    + "\n  Input was: " + json, e);
        }
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /** Simple ${varName} substitution — delegates to the variable scope. */
    private static String replaceVars(String template, VariableScope scope) {
        StringBuilder sb = new StringBuilder(template);
        int start;
        while ((start = sb.indexOf("${")) >= 0) {
            int end = sb.indexOf("}", start);
            if (end < 0) break;
            String varName = sb.substring(start + 2, end);
            Object val = scope.getVariableValue(varName);
            String replacement = val != null ? val.toString() : "";
            sb.replace(start, end + 1, replacement);
        }
        return sb.toString();
    }
}
