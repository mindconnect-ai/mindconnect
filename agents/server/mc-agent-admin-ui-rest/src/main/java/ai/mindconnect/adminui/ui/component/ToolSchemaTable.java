package ai.mindconnect.adminui.ui.component;

import ai.mindconnect.ui.model.UiNode;
import ai.mindconnect.ui.model.UiTable;

import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Renders a tool's JSON-Schema parameters as a readable table — one row per
 * parameter with its type (enums and array item types spelled out), whether
 * it is required, and its description — instead of dumping the raw schema
 * JSON into a textarea. Used by the tool detail and edit views.
 */
final class ToolSchemaTable {

    private ToolSchemaTable() {}

    /** The parameters table, or {@code null} when the schema has no properties to show. */
    static UiNode render(String id, Object schemaObject) {
        return render(id, schemaObject, "Parameters");
    }

    /** Same, with a caller-chosen table title (e.g. "Config Overrides"). */
    static UiNode render(String id, Object schemaObject, String title) {
        if (!(schemaObject instanceof Map<?, ?> schema)
                || !(schema.get("properties") instanceof Map<?, ?> properties)
                || properties.isEmpty()) {
            return null;
        }
        Set<String> required = new HashSet<>();
        if (schema.get("required") instanceof Collection<?> names) {
            names.forEach(name -> required.add(String.valueOf(name)));
        }

        UiTable table = UiTable.of(id, title)
                .column(UiTable.Column.text("param", "Parameter"))
                .column(UiTable.Column.text("type", "Type"))
                .column(UiTable.Column.text("required", "Required"))
                .column(UiTable.Column.text("description", "Description"));
        properties.forEach((name, spec) -> table.row(Map.of(
                "id", String.valueOf(name),
                "param", String.valueOf(name),
                "type", typeWithDefault(spec),
                "required", required.contains(String.valueOf(name)) ? "yes" : "",
                "description", descriptionOf(spec))));
        return table;
    }

    /** {@code string (a | b)}, {@code array<string>}, {@code string (path)}, … */
    private static String typeOf(Object specObject) {
        if (!(specObject instanceof Map<?, ?> spec)) {
            return "";
        }
        String type = str(spec.get("type"));
        if (spec.get("enum") instanceof Collection<?> values && !values.isEmpty()) {
            return type + " (" + values.stream().map(String::valueOf)
                    .collect(Collectors.joining(" | ")) + ")";
        }
        if ("array".equals(type) && spec.get("items") instanceof Map<?, ?> items) {
            String itemType = str(items.get("type"));
            return itemType.isEmpty() ? "array" : "array<" + itemType + ">";
        }
        String format = str(spec.get("format"));
        return format.isEmpty() ? type : type + " (" + format + ")";
    }

    /** {@code string (a | b), default: none} — defaults matter for overrides. */
    private static String typeWithDefault(Object specObject) {
        String type = typeOf(specObject);
        if (specObject instanceof Map<?, ?> spec && spec.get("default") != null) {
            return type + ", default: " + spec.get("default");
        }
        return type;
    }

    private static String descriptionOf(Object specObject) {
        return specObject instanceof Map<?, ?> spec ? str(spec.get("description")) : "";
    }

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
