package ai.mindconnect.agent.runtime.capabilities.prompt;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import ai.mindconnect.agent.runtime.api.port.out.PromptRenderer;

/**
 * Replaces {@code {{name}}} with a value and does nothing else. No loops,
 * no conditions: instructions that need them get a real template engine
 * behind the same port.
 */
public final class VariablePromptRenderer implements PromptRenderer {

    private static final Pattern VARIABLE = Pattern.compile("\\{\\{\\s*([\\w.]+)\\s*}}");

    @Override
    public String render(String template, Map<String, Object> variables) {
        // A name nobody provided stays as written: a visible gap is easier to
        // find than a silent one.
        return VARIABLE.matcher(template).replaceAll(match ->
                Matcher.quoteReplacement(variables.containsKey(match.group(1))
                        ? String.valueOf(variables.get(match.group(1)))
                        : match.group()));
    }
}
