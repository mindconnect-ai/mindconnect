package ai.mindconnect.agent.tool;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Makes optional tool parameters mandatory for one agent. An {@link AgentTool}
 * override under the {@code requiredParams} key lists parameter names that this
 * agent must supply: they are added to the schema's {@code required} array, and
 * a call that omits one is refused before the tool runs.
 *
 * <p>The counterpart to {@link PinnedParamsTool}: pinning takes a decision away
 * from the model, requiring insists the model make it. Both are agent-level, so
 * one binding can tighten a tool without changing it for everyone.
 *
 * <p>It exists because prose does not carry far enough. A tool description
 * saying "always pass {@code query}" is followed by a large model and ignored by
 * a small one perhaps half the time — and each omission costs the very context
 * the parameter was meant to save. The schema is the one instruction every model
 * reads the same way.
 *
 * <p>Applied centrally by {@link SpiToolRegistry#resolve}, so it works for every
 * tool source without the individual tool knowing about it.
 *
 * <p>Example agent-tool override:
 * <pre>{@code
 *   "overrides": { "requiredParams": ["query"] }
 * }</pre>
 * makes {@code web_read} unusable for that agent without saying what it is
 * looking for.
 */
public final class RequiredParamsTool implements Tool {

    /** Key inside {@link AgentTool#overrides()} holding the list of names. */
    public static final String OVERRIDE_KEY = "requiredParams";

    private final Tool delegate;
    private final Set<String> required;

    private RequiredParamsTool(Tool delegate, Set<String> required) {
        this.delegate = delegate;
        this.required = required;
    }

    /** Wraps {@code delegate} when the agent tool requires parameters; identity otherwise. */
    public static Tool wrap(AgentTool agentTool, Tool delegate) {
        if (agentTool == null || delegate == null) {
            return delegate;
        }
        if (!(agentTool.overrides().get(OVERRIDE_KEY) instanceof Iterable<?> raw)) {
            return delegate;
        }
        Set<String> names = new LinkedHashSet<>();
        for (Object name : raw) {
            String s = String.valueOf(name).strip();
            if (!s.isEmpty()) names.add(s);
        }
        return names.isEmpty() ? delegate : new RequiredParamsTool(delegate, names);
    }

    @Override
    public String name() {
        return delegate.name();
    }

    @Override
    public String description() {
        return delegate.description();
    }

    @Override
    public Map<String, Object> parametersSchema() {
        Map<String, Object> schema = new LinkedHashMap<>(delegate.parametersSchema());
        List<String> merged = new ArrayList<>();
        if (schema.get("required") instanceof Iterable<?> existing) {
            existing.forEach(name -> merged.add(String.valueOf(name)));
        } else if (schema.get("required") instanceof String[] existing) {
            merged.addAll(List.of(existing));
        }
        // Only names the tool actually offers — requiring a parameter that does
        // not exist would produce a schema no call can satisfy.
        Object properties = schema.get("properties");
        for (String name : required) {
            boolean offered = !(properties instanceof Map<?, ?> map) || map.containsKey(name);
            if (offered && !merged.contains(name)) merged.add(name);
        }
        schema.put("required", merged);
        return schema;
    }

    @Override
    public String execute(Map<String, Object> arguments) {
        List<String> missing = new ArrayList<>();
        for (String name : required) {
            Object value = arguments == null ? null : arguments.get(name);
            if (value == null || (value instanceof String s && s.isBlank())) missing.add(name);
        }
        if (!missing.isEmpty()) {
            // An error the model can act on inside the same turn, rather than a
            // silent run that returns the wrong shape of result.
            return "Error: this tool requires " + String.join(", ", missing)
                    + ". Call it again with " + (missing.size() == 1 ? "that parameter" : "those parameters")
                    + " set.";
        }
        return delegate.execute(arguments);
    }

    @Override
    public boolean streamsResultToUser() {
        return delegate.streamsResultToUser();
    }
}
