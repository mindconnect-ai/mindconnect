package ai.mindconnect.workflow.admin.ui;

import ai.mindconnect.schema.Schema;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Turns the strings a form submits into the types their {@link Schema} declares,
 * at the boundary between the UI and the engine.
 *
 * <p>An HTML form only ever hands back strings. But a parameter declared as an
 * array, a number or a boolean should reach the workflow as that — a for-each
 * over an {@code array} param wants a real list, not the text {@code
 * "[\"a\"]"}. The schema already says what each param is; this reads that and
 * coerces the value once, here, so the engine receives properly-typed inputs and
 * nothing downstream has to guess or re-parse.
 *
 * <p>A value that will not parse is left as the string it came in as — the run
 * then fails with the engine's own, specific error rather than a vague one
 * thrown here.
 */
public final class ParamCoercion {

    private static final ObjectMapper JSON = new ObjectMapper();

    private ParamCoercion() {}

    /** Coerces every submitted value whose param the schema types. */
    public static Map<String, Object> coerce(Schema schema, Map<String, Object> submitted) {
        Map<String, Object> out = new LinkedHashMap<>(submitted);
        if (schema == null || schema.getProperties() == null) {
            return out;
        }
        schema.getProperties().forEach((name, prop) -> {
            if (out.containsKey(name)) {
                out.put(name, coerceValue(prop, out.get(name)));
            }
        });
        return out;
    }

    private static Object coerceValue(Schema prop, Object value) {
        if (!(value instanceof String text) || text.isBlank()) {
            return value; // already typed, or nothing to coerce
        }
        String s = text.trim();
        try {
            return switch (prop.getType()) {
                case INTEGER -> Long.parseLong(s);
                case NUMBER -> Double.parseDouble(s);
                case BOOLEAN -> Boolean.parseBoolean(s);
                case ARRAY, OBJECT -> JSON.readValue(s, Object.class); // JSON text -> list/map
                default -> value;
            };
        } catch (Exception malformed) {
            return value; // let the engine report what is actually wrong with it
        }
    }
}
