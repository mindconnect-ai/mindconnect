package ai.mindconnect.schema;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Checks a value against a {@link Schema} and reports, by path, everything wrong
 * with it — rather than throwing on the first problem.
 *
 * <p>The point is that the schema an input is declared with and the check its
 * value is put through are the <em>same</em> schema, instead of a form
 * description and a hand-written {@code execute()} guard drifting apart. It
 * validates exactly what the model expresses (type, required, enum membership,
 * array elements, object fields) and nothing it does not.
 */
public final class SchemaValidator {

    private SchemaValidator() {}

    /** Empty when {@code value} satisfies {@code schema}. */
    public static List<String> validate(Schema schema, Object value) {
        List<String> errors = new ArrayList<>();
        check(schema, value, "", errors);
        return errors;
    }

    public static boolean isValid(Schema schema, Object value) {
        return validate(schema, value).isEmpty();
    }

    private static void check(Schema schema, Object value, String path, List<String> errors) {
        if (value == null) {
            return; // presence is enforced by the object's required list, not here
        }
        switch (schema.getType()) {
            case STRING -> expect(value instanceof String, path, "a string", errors);
            case BOOLEAN -> expect(value instanceof Boolean, path, "a boolean", errors);
            case INTEGER -> expect(isInteger(value), path, "an integer", errors);
            case NUMBER -> expect(value instanceof Number, path, "a number", errors);
            case ENUM -> {
                if (!schema.getEnumValues().contains(String.valueOf(value))) {
                    errors.add(at(path) + "must be one of " + schema.getEnumValues()
                            + ", was '" + value + "'");
                }
            }
            case ARRAY -> checkArray(schema, value, path, errors);
            case OBJECT -> checkObject(schema, value, path, errors);
        }
    }

    private static void checkArray(Schema schema, Object value, String path, List<String> errors) {
        if (!(value instanceof List<?> list)) {
            expect(false, path, "an array", errors);
            return;
        }
        if (schema.getItems() != null) {
            for (int i = 0; i < list.size(); i++) {
                check(schema.getItems(), list.get(i), path + "[" + i + "]", errors);
            }
        }
    }

    private static void checkObject(Schema schema, Object value, String path, List<String> errors) {
        if (!(value instanceof Map<?, ?> map)) {
            expect(false, path, "an object", errors);
            return;
        }
        for (String name : schema.getRequired()) {
            if (map.get(name) == null) {
                errors.add(at(path) + "is missing required field '" + name + "'");
            }
        }
        schema.getProperties().forEach((name, sub) -> {
            Object field = map.get(name);
            if (field != null) {
                check(sub, field, path.isEmpty() ? name : path + "." + name, errors);
            }
        });
    }

    private static boolean isInteger(Object value) {
        return value instanceof Integer || value instanceof Long
                || (value instanceof Number n && n.doubleValue() == Math.floor(n.doubleValue()));
    }

    private static void expect(boolean ok, String path, String what, List<String> errors) {
        if (!ok) {
            errors.add(at(path) + "must be " + what);
        }
    }

    private static String at(String path) {
        return path.isEmpty() ? "value " : "'" + path + "' ";
    }
}
