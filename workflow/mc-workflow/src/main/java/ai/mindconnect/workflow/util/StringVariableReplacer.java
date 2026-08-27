package ai.mindconnect.workflow.util;

import ai.mindconnect.pathaccessor.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Resolves {@code ${varName}} and {@code ${varName.path}} placeholders in strings.
 *
 * <p>Instantiate via the no-arg constructor (which registers {@link MapPathAccessor}
 * by default) or build a custom instance and add further {@link PathAccessor}
 * implementations to support Beans, Records, JSON strings, etc.:
 *
 * <pre>
 *   StringVariableReplacer replacer = new StringVariableReplacer();
 *   replacer.addPathAccessor(new BeanPathAccessor());
 *   ctxFactory.setStringVariableReplacer(replacer);
 * </pre>
 *
 * <p>Path traversal algorithm for {@code ${root.a.b}}:
 * <ol>
 *   <li>Look up {@code root} from the scope.
 *   <li>Find the first {@link PathAccessor} where {@link PathAccessor#supports} returns true.
 *   <li>Ask that accessor to read {@code "a.b"} from the root value.
 *   <li>Fall back to a flat lookup of the full key {@code "root.a.b"} when no accessor matches
 *       (backwards compatibility for flat variable names that contain dots).
 * </ol>
 */
public class StringVariableReplacer {

    private final List<PathAccessor> pathAccessors = new ArrayList<>();

    /**
     * Creates a replacer with the built-in {@link MapPathAccessor},{@link BeanPathAccessor},{@link RecordPathAccessor} registered.
     */
    public StringVariableReplacer() {
        pathAccessors.add(new MapPathAccessor());
        pathAccessors.add(new BeanPathAccessor());
        pathAccessors.add(new RecordPathAccessor());
    }

    /**
     * Appends a {@link PathAccessor} to the end of the accessor chain.
     * Accessors are tried in registration order; the first matching one wins.
     */
    public void addPathAccessor(PathAccessor accessor) {
        pathAccessors.add(accessor);
    }

    public List<PathAccessor> getPathAccessors() {
        return pathAccessors;
    }

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    /**
     * Replaces all {@code ${...}} placeholders in {@code input} using values
     * provided by {@code getVariableValue}.  Unknown placeholders are left as-is.
     */
    public String replaceVars(String input, Function<String, Object> getVariableValue) {
        if (input == null || !input.contains("${")) return input;

        StringBuilder result = new StringBuilder(input);
        int start = result.indexOf("${", 0);

        while (start != -1) {
            int end = result.indexOf("}", start);
            if (end == -1) break;

            String placeholder = result.substring(start + 2, end);
            Object value = resolve(placeholder, getVariableValue);

            if (value != null) {
                String replacement = stringify(value);
                result.replace(start, end + 1, replacement);
                start = result.indexOf("${", start + replacement.length());
            } else {
                start = result.indexOf("${", end + 1);
            }
        }
        return result.toString();
    }

    /**
     * Returns all resolved placeholder expressions and their values,
     * without modifying the input string.
     */
    public Map<String, String> resolveVars(String input, Function<String, Object> getVariableValue) {
        Map<String, String> map = new LinkedHashMap<>();
        if (input == null) return map;

        int start = input.indexOf("${", 0);
        while (start != -1) {
            int end = input.indexOf("}", start);
            if (end == -1) break;

            String placeholder = input.substring(start + 2, end);
            Object value = resolve(placeholder, getVariableValue);
            if (value != null) {
                map.put(placeholder, stringify(value));
            }
            start = input.indexOf("${", end + 1);
        }
        return map;
    }

    // -----------------------------------------------------------------------
    // Internal helpers
    // -----------------------------------------------------------------------

    private Object resolve(String placeholder, Function<String, Object> getVariableValue) {
        int dotIndex = placeholder.indexOf('.');
        String firstPart = dotIndex == -1 ? placeholder : placeholder.substring(0, dotIndex);
        String remainder = dotIndex == -1 ? null : placeholder.substring(dotIndex + 1);

        // Parse the root segment — may carry an index, e.g. "tags[1]"
        PathSegment rootSeg = PathSegment.parse(firstPart);
        Object root = getVariableValue.apply(rootSeg.name);

        // Apply index on the root value if present (e.g. ${tags[1]})
        root = rootSeg.applyIndex(root);

        if (remainder == null) {
            return root;
        }

        if (root != null) {
            for (PathAccessor accessor : pathAccessors) {
                if (accessor.supports(root)) {
                    return accessor.read(root, remainder);
                }
            }
        }

        // Fallback: try the full dotted key as a flat variable name
        return getVariableValue.apply(placeholder);
    }

    private static String stringify(Object value) {
        return value.toString();
    }
}
