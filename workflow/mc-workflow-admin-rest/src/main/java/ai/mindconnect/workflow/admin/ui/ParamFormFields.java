package ai.mindconnect.workflow.admin.ui;

import ai.mindconnect.schema.Schema;
import ai.mindconnect.ui.model.UiAction;
import ai.mindconnect.ui.model.UiField;
import ai.mindconnect.ui.model.UiForm;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Turns an object {@link Schema} into form fields — one place, so a workflow's
 * run form and a halt's resume dialog render inputs the same way.
 *
 * <p>Each property becomes a typed field: an enum is a dropdown, a boolean a
 * checkbox, a multiline string a textarea, a number a number box. A nested array
 * or object has no honest flat-field rendering, so it falls back to a JSON
 * textarea — the same escape hatch the step editor uses — rather than pretending
 * to be something it is not.
 */
public final class ParamFormFields {

    private ParamFormFields() {}

    /** Adds a field per property of {@code schema} (an object schema) to {@code form}. */
    public static void addTo(UiForm form, Schema schema) {
        addTo(form, schema, null, null);
    }

    /**
     * Like {@link #addTo(UiForm, Schema)}, additionally prefilled from
     * {@code values} (e.g. the last run's inputs) and — for {@code PATH}
     * properties — with a Browse… button on the field when
     * {@code browseUrlForParam} supplies a chooser URL for the property name.
     */
    public static void addTo(UiForm form, Schema schema,
                             Function<String, String> browseUrlForParam,
                             Map<String, String> values) {
        if (schema == null || schema.getProperties() == null) {
            return;
        }
        schema.getProperties().forEach((name, prop) -> {
            String value = values == null ? null : values.get(name);
            String browseUrl = browseUrlForParam == null ? null : browseUrlForParam.apply(name);
            form.field(field(name, prop, schema.isRequired(name), value, browseUrl));
        });
    }

    /** Whether the schema declares any inputs at all. */
    public static boolean isEmpty(Schema schema) {
        return schema == null || schema.getProperties() == null || schema.getProperties().isEmpty();
    }

    private static UiField field(String name, Schema prop, boolean required) {
        return field(name, prop, required, null);
    }

    public static UiField field(String name, Schema prop, boolean required, String value) {
        return field(name, prop, required, value, null);
    }

    /**
     * Builds the field for one property; {@code value} (when non-null) overrides
     * the schema default — used e.g. by the file-chooser to re-render a path
     * field with the picked path. A non-null {@code browseUrl} puts a Browse…
     * button on the row of a {@code PATH} property.
     */
    public static UiField field(String name, Schema prop, boolean required, String value, String browseUrl) {
        String label = label(name, prop);
        String text = value != null ? value : defaultText(prop);
        UiField field = switch (prop.getType()) {
            case ENUM -> UiField.select(name, label, text, options(prop));
            case BOOLEAN -> UiField.bool(name, label, Boolean.parseBoolean(text));
            case INTEGER, NUMBER -> UiField.number(name, label,
                    value != null ? value : prop.getDefaultValue());
            case ARRAY, OBJECT -> UiField.textarea(name, label + " (JSON)", value)
                    .hint("A " + prop.getType().name().toLowerCase() + " — enter it as JSON.");
            default -> prop.getFormat() == Schema.Format.MULTILINE
                    ? UiField.textarea(name, label, text)
                    : UiField.text(name, label, text);
        };
        field.asEditable();
        if (required) {
            field.asRequired();
        }
        if (prop.getDescription() != null && !prop.getDescription().isBlank()) {
            field.hint(prop.getDescription());
        }
        if (browseUrl != null && prop.getFormat() == Schema.Format.PATH) {
            field.trailing(UiAction.secondary("browse-" + name, "Browse…")
                    .dispatch("GET", browseUrl));
        }
        return field;
    }

    private static String label(String name, Schema prop) {
        return name; // the schema has no separate title in this model; the name is the label
    }

    private static List<UiField.Option> options(Schema prop) {
        List<UiField.Option> options = new ArrayList<>();
        for (String value : prop.getEnumValues()) {
            options.add(UiField.Option.of(value, value));
        }
        return options;
    }

    private static String defaultText(Schema prop) {
        return prop.getDefaultValue() == null ? null : String.valueOf(prop.getDefaultValue());
    }
}
