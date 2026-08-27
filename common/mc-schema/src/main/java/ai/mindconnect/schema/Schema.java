package ai.mindconnect.schema;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A typed, recursive model of the slice of JSON Schema this codebase actually
 * uses to declare inputs: scalars, enums, arrays and objects, plus {@code
 * description}, {@code required} and {@code default}.
 *
 * <p>It exists to replace hand-nested {@code Map<String,Object>} literals. You
 * build with a fluent API —
 * <pre>{@code
 * Schema todo = Schema.object()
 *     .prop("content", Schema.string().description("Imperative form"))
 *     .prop("status", Schema.enumOf("pending", "in_progress", "completed"))
 *     .require("content", "status");
 * Schema input = Schema.object().prop("todos", Schema.array(todo).description("Full list"));
 * }</pre>
 * and the nested map appears only at the edge, via {@link #toMap()}, in exactly
 * the JSON-Schema shape an LLM tool or MCP expects. {@link #fromMap(Map)} reads
 * one back — so a schema handed in from outside round-trips.
 *
 * <p>What it deliberately does <em>not</em> model: {@code oneOf}/{@code anyOf},
 * {@code $ref}, {@code additionalProperties}, numeric/string constraints. None
 * are used here, and leaving them out is what keeps this a schema you can render
 * a form from rather than a validator you have to fear. A foreign schema that
 * uses them still round-trips as data (unknown keys are preserved on {@link
 * #extra}); it simply is not introspected.
 */
@Data
@NoArgsConstructor
public class Schema {

    /** The JSON-Schema {@code type}. {@code ENUM} is this model's shorthand for
     *  a string constrained by {@code enum}; it serialises as {@code "string"}. */
    public enum Type { STRING, INTEGER, NUMBER, BOOLEAN, ARRAY, OBJECT, ENUM }

    /** A rendering hint, not part of validation. {@code DATE} maps to JSON
     *  Schema's {@code format: date}; the others use non-standard format tags
     *  so they survive persistence round-trips. {@code PATH} marks a string
     *  holding a server-side file or directory path — editors may offer a
     *  file chooser. */
    public enum Format { MULTILINE, PASSWORD, DATE, PATH }

    private Type type = Type.STRING;
    private String description;

    /** Allowed values when {@link #type} is {@link Type#ENUM}. */
    private List<String> enumValues = new ArrayList<>();

    private Object defaultValue;
    private Format format;

    /** Element schema when {@link #type} is {@link Type#ARRAY}. */
    private Schema items;

    /** Ordered fields when {@link #type} is {@link Type#OBJECT}. */
    private Map<String, Schema> properties = new LinkedHashMap<>();

    /** Names within {@link #properties} that must be present. */
    private List<String> required = new ArrayList<>();

    /**
     * JSON-Schema keys this model does not understand, kept verbatim so a
     * foreign schema survives a {@link #fromMap} / {@link #toMap} round trip.
     */
    private Map<String, Object> extra = new LinkedHashMap<>();

    // -----------------------------------------------------------------------
    // Builders
    // -----------------------------------------------------------------------

    public static Schema string()  { return of(Type.STRING); }
    public static Schema integer() { return of(Type.INTEGER); }
    public static Schema number()  { return of(Type.NUMBER); }
    public static Schema bool()    { return of(Type.BOOLEAN); }
    public static Schema object()  { return of(Type.OBJECT); }

    public static Schema array(Schema items) {
        Schema s = of(Type.ARRAY);
        s.items = items;
        return s;
    }

    public static Schema enumOf(String... values) {
        Schema s = of(Type.ENUM);
        s.enumValues = new ArrayList<>(List.of(values));
        return s;
    }

    private static Schema of(Type type) {
        Schema s = new Schema();
        s.type = type;
        return s;
    }

    public Schema description(String description) {
        this.description = description;
        return this;
    }

    public Schema defaultValue(Object value) {
        this.defaultValue = value;
        return this;
    }

    public Schema format(Format format) {
        this.format = format;
        return this;
    }

    public Schema multiline() {
        return format(Format.MULTILINE);
    }

    /** Marks a string as a server-side file/directory path — editors may offer a file chooser. */
    public Schema path() {
        return format(Format.PATH);
    }

    /** Adds a field to an object schema. Order of insertion is preserved. */
    public Schema prop(String name, Schema schema) {
        this.properties.put(name, schema);
        return this;
    }

    /** Marks fields required. Names should exist in {@link #properties}. */
    public Schema require(String... names) {
        this.required.addAll(List.of(names));
        return this;
    }

    /** True when {@code name} is a required field of this object schema. */
    public boolean isRequired(String name) {
        return required.contains(name);
    }

    // -----------------------------------------------------------------------
    // JSON-Schema map — the wire form (LLM tools, MCP, stored definitions)
    // -----------------------------------------------------------------------

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>(extra);
        map.put("type", jsonType());
        if (description != null && !description.isBlank()) {
            map.put("description", description);
        }
        if (type == Type.ENUM && !enumValues.isEmpty()) {
            map.put("enum", new ArrayList<>(enumValues));
        }
        if (defaultValue != null) {
            map.put("default", defaultValue);
        }
        if (format != null) {
            map.put("format", format.name().toLowerCase());
        }
        if (type == Type.ARRAY && items != null) {
            map.put("items", items.toMap());
        }
        if (type == Type.OBJECT) {
            Map<String, Object> props = new LinkedHashMap<>();
            properties.forEach((name, schema) -> props.put(name, schema.toMap()));
            map.put("properties", props);
            if (!required.isEmpty()) {
                map.put("required", new ArrayList<>(required));
            }
        }
        return map;
    }

    private String jsonType() {
        return switch (type) {
            case ENUM -> "string";
            case INTEGER -> "integer";
            case NUMBER -> "number";
            case BOOLEAN -> "boolean";
            case ARRAY -> "array";
            case OBJECT -> "object";
            default -> "string";
        };
    }

    /**
     * Reads a JSON-Schema map into this model. Keys it understands become typed
     * fields; anything else is kept on {@link #extra} so it is not lost. A bare
     * list of strings is accepted as a shorthand for an object of string
     * properties — the shape older workflow files stored their params as.
     */
    @SuppressWarnings("unchecked")
    public static Schema fromMap(Object raw) {
        if (raw instanceof List<?> names) { // legacy: ["a","b"] -> object{a,b}
            Schema object = object();
            for (Object name : names) {
                object.prop(String.valueOf(name), string());
            }
            return object;
        }
        if (!(raw instanceof Map<?, ?> map)) {
            return string();
        }

        Schema schema = new Schema();
        Object enumValues = map.get("enum");
        String declaredType = map.get("type") == null ? null : String.valueOf(map.get("type"));

        if (enumValues instanceof List<?> values) {
            schema.type = Type.ENUM;
            values.forEach(v -> schema.enumValues.add(String.valueOf(v)));
        } else {
            schema.type = parseType(declaredType);
        }

        Object description = map.get("description");
        if (description != null) schema.description = String.valueOf(description);
        schema.defaultValue = map.get("default");
        Object format = map.get("format");
        if (format != null) {
            try {
                schema.format = Format.valueOf(String.valueOf(format).toUpperCase());
            } catch (IllegalArgumentException ignored) {
                // Format tags this model doesn't know (e.g. JSON Schema's
                // "email") are tolerated but not represented.
            }
        }

        if (schema.type == Type.ARRAY && map.get("items") != null) {
            schema.items = fromMap(map.get("items"));
        }
        if (schema.type == Type.OBJECT) {
            Object props = map.get("properties");
            if (props instanceof Map<?, ?> propMap) {
                propMap.forEach((name, sub) -> schema.properties.put(String.valueOf(name), fromMap(sub)));
            }
            Object required = map.get("required");
            if (required instanceof List<?> req) {
                req.forEach(r -> schema.required.add(String.valueOf(r)));
            }
        }

        // Keep anything we did not interpret, so a foreign schema round-trips.
        for (Map.Entry<String, Object> e : ((Map<String, Object>) map).entrySet()) {
            if (!KNOWN_KEYS.contains(e.getKey())) {
                schema.extra.put(e.getKey(), e.getValue());
            }
        }
        return schema;
    }

    private static final List<String> KNOWN_KEYS =
            List.of("type", "description", "enum", "default", "format", "items", "properties", "required");

    private static Type parseType(String declared) {
        if (declared == null) return Type.OBJECT; // a schema with only properties
        return switch (declared) {
            case "integer" -> Type.INTEGER;
            case "number" -> Type.NUMBER;
            case "boolean" -> Type.BOOLEAN;
            case "array" -> Type.ARRAY;
            case "object" -> Type.OBJECT;
            default -> Type.STRING;
        };
    }
}
