package ai.mindconnect.agent.tool;

import ai.mindconnect.agent.tool.AgentTool;
import ai.mindconnect.agent.tool.Tool;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Enforces agent-pinned tool parameters. An {@link AgentTool} override under
 * the {@code params} key is a map of parameter name → value that the agent's
 * configuration dictates: those values are written over whatever the LLM
 * passed <em>before</em> the tool executes, and the pinned parameters are
 * stripped from the schema the LLM sees — it cannot set what it is never
 * offered.
 *
 * <p>Applied centrally by {@link SpiToolRegistry#resolve}, so pinning works
 * uniformly for every tool source (built-in factories and multi-tool
 * providers alike) without the individual tool knowing about it.
 *
 * <p>Example agent-tool override:
 * <pre>{@code
 *   "overrides": { "params": { "language": "python" } }
 * }</pre>
 * makes {@code code_execute} a Python-only tool for that agent: the LLM is
 * not offered a {@code language} parameter, and any value it passed anyway
 * would be replaced.
 */
public final class PinnedParamsTool implements Tool {

    /** Key inside {@link AgentTool#overrides()} holding the pinned map. */
    public static final String OVERRIDE_KEY = "params";

    private final Tool delegate;
    private final Map<String, Object> pinned;

    private PinnedParamsTool(Tool delegate, Map<String, Object> pinned) {
        this.delegate = delegate;
        this.pinned = pinned;
    }

    /** Wraps {@code delegate} when the agent tool pins parameters; identity otherwise. */
    public static Tool wrap(AgentTool agentTool, Tool delegate) {
        if (agentTool == null || delegate == null) {
            return delegate;
        }
        if (!(agentTool.overrides().get(OVERRIDE_KEY) instanceof Map<?, ?> map) || map.isEmpty()) {
            return delegate;
        }
        Map<String, Object> pinned = new LinkedHashMap<>();
        map.forEach((k, v) -> pinned.put(String.valueOf(k), v));
        return new PinnedParamsTool(delegate, pinned);
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
        if (schema.get("properties") instanceof Map<?, ?> properties) {
            Map<String, Object> visible = new LinkedHashMap<>();
            properties.forEach((k, v) -> {
                if (!pinned.containsKey(String.valueOf(k))) {
                    visible.put(String.valueOf(k), v);
                }
            });
            schema.put("properties", visible);
        }
        if (schema.get("required") instanceof List<?> required) {
            schema.put("required", required.stream()
                    .map(String::valueOf)
                    .filter(name -> !pinned.containsKey(name))
                    .toList());
        }
        return schema;
    }

    @Override
    public String execute(Map<String, Object> arguments) {
        Map<String, Object> merged = new LinkedHashMap<>(arguments == null ? Map.of() : arguments);
        merged.putAll(pinned);
        return delegate.execute(merged);
    }

    @Override
    public boolean streamsResultToUser() {
        return delegate.streamsResultToUser();
    }
}
