package ai.mindconnect.adminui.service;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Generates an example JSON payload from a JSON-Schema {@code Map} (the shape
 * returned by {@code Tool.parametersSchema()}). Used to pre-fill the tool-test
 * arguments box with a skeleton the admin can edit, instead of an empty
 * {@code {}}.
 *
 * <p>Best-effort and intentionally simple: it walks {@code properties},
 * picking a placeholder per declared {@code type} (enum → first option,
 * string → "", number → 0, boolean → false, array → [example], object →
 * nested skeleton). Unknown/Untyped fields become {@code null}.
 */
public final class JsonSchemaSample {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_DEPTH = 5;

    private JsonSchemaSample() {}

    /**
     * Returns a pretty-printed example JSON object for {@code schema}, or
     * {@code "{}\n"} when the schema is null/empty or has no properties.
     */
    public static String example(Map<String, Object> schema) {
        Object value = sample(schema, 0);
        if (!(value instanceof Map<?, ?> m) || m.isEmpty()) {
            return "{}\n";
        }
        try {
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(value) + "\n";
        } catch (Exception e) {
            return "{}\n";
        }
    }

    @SuppressWarnings("unchecked")
    private static Object sample(Object node, int depth) {
        if (depth > MAX_DEPTH || !(node instanceof Map<?, ?> raw)) return null;
        Map<String, Object> schema = (Map<String, Object>) raw;

        // Enum wins regardless of type — use the first allowed value.
        Object enumVals = schema.get("enum");
        if (enumVals instanceof List<?> l && !l.isEmpty()) return l.get(0);
        if (enumVals instanceof Object[] a && a.length > 0) return a[0];

        String type = asType(schema.get("type"));
        return switch (type == null ? "" : type) {
            case "object" -> objectSample(schema, depth);
            case "array" -> arraySample(schema, depth);
            case "string" -> "";
            case "integer", "number" -> 0;
            case "boolean" -> false;
            // No/unknown type: if it has properties treat as object, else null.
            default -> schema.containsKey("properties") ? objectSample(schema, depth) : null;
        };
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> objectSample(Map<String, Object> schema, int depth) {
        var out = new LinkedHashMap<String, Object>();
        Object props = schema.get("properties");
        if (props instanceof Map<?, ?> p) {
            for (var entry : p.entrySet()) {
                out.put(String.valueOf(entry.getKey()), sample(entry.getValue(), depth + 1));
            }
        }
        return out;
    }

    private static List<Object> arraySample(Map<String, Object> schema, int depth) {
        var out = new ArrayList<>();
        Object items = schema.get("items");
        if (items instanceof Map<?, ?>) {
            out.add(sample(items, depth + 1));
        }
        return out;
    }

    /** JSON-Schema {@code type} can be a string or an array of strings. */
    private static String asType(Object type) {
        if (type instanceof String s) return s;
        if (type instanceof List<?> l && !l.isEmpty()) return String.valueOf(l.get(0));
        if (type instanceof Object[] a && a.length > 0) return String.valueOf(a[0]);
        return null;
    }
}
