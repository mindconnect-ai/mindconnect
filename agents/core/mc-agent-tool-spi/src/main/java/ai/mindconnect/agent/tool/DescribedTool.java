package ai.mindconnect.agent.tool;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A tool wearing the description an operator gave it.
 *
 * <p>Sits in the same chain as {@link AliasTool} and the parameter guards,
 * and does the same kind of thing: leave the tool's work alone, change what
 * the model is told about it. {@link #execute} is untouched — an override
 * that changed behaviour would be a different tool, not a better label.
 *
 * <p>Parameter descriptions are replaced inside the schema, one property at
 * a time. Types, {@code required} and enums stay exactly as the source
 * reported them: a caller who edits those builds calls the server rejects,
 * and the failure surfaces at run time rather than at the form.
 */
public final class DescribedTool implements Tool {

    private final Tool delegate;
    private final String description;
    private final Map<String, String> parameterDescriptions;

    private DescribedTool(Tool delegate, String description, Map<String, String> parameterDescriptions) {
        this.delegate = delegate;
        this.description = description;
        this.parameterDescriptions = parameterDescriptions;
    }

    /**
     * Wraps {@code delegate} when {@code settings} actually says something
     * about its text; returns it unchanged otherwise, so a tool nobody
     * touched carries no wrapper at all.
     */
    public static Tool wrap(Tool delegate, ToolSettings settings) {
        if (delegate == null || settings == null) {
            return delegate;
        }
        boolean hasDescription = settings.description() != null && !settings.description().isBlank();
        if (!hasDescription && settings.parameterDescriptions().isEmpty()) {
            return delegate;
        }
        return new DescribedTool(delegate,
                hasDescription ? settings.description() : null,
                settings.parameterDescriptions());
    }

    /**
     * Wraps {@code delegate} with a single replacement description; returns
     * it unchanged when there is none. This is the agent-level override —
     * what an agent definition says about a tool it binds.
     */
    public static Tool withDescription(Tool delegate, String description) {
        if (delegate == null || description == null || description.isBlank()) {
            return delegate;
        }
        return new DescribedTool(delegate, description, Map.of());
    }

    @Override
    public String name() {
        return delegate.name();
    }

    @Override
    public String description() {
        return description != null ? description : delegate.description();
    }

    @Override
    public Map<String, Object> parametersSchema() {
        Map<String, Object> schema = delegate.parametersSchema();
        if (parameterDescriptions.isEmpty() || schema == null) {
            return schema;
        }
        Object properties = schema.get("properties");
        if (!(properties instanceof Map<?, ?> propertyMap)) {
            return schema;                       // nothing shaped like parameters
        }
        Map<String, Object> patchedProperties = new LinkedHashMap<>();
        boolean changed = false;
        for (Map.Entry<?, ?> property : propertyMap.entrySet()) {
            String parameter = String.valueOf(property.getKey());
            Object definition = property.getValue();
            String replacement = parameterDescriptions.get(parameter);
            if (replacement != null && !replacement.isBlank() && definition instanceof Map<?, ?> fields) {
                Map<String, Object> patched = new LinkedHashMap<>();
                fields.forEach((key, value) -> patched.put(String.valueOf(key), value));
                patched.put("description", replacement);
                patchedProperties.put(parameter, patched);
                changed = true;
            } else {
                patchedProperties.put(parameter, definition);
            }
        }
        if (!changed) {
            return schema;
        }
        Map<String, Object> patchedSchema = new LinkedHashMap<>(schema);
        patchedSchema.put("properties", patchedProperties);
        return patchedSchema;
    }

    @Override
    public String execute(Map<String, Object> arguments) {
        return delegate.execute(arguments);
    }

    @Override
    public boolean streamsResultToUser() {
        return delegate.streamsResultToUser();
    }
}
