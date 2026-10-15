package ai.mindconnect.agent.tools.workflow.step;

import ai.mindconnect.agent.tool.ToolCallScope;
import ai.mindconnect.workflow.execution.BaseStepInstance;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Map;

/**
 * Executes a {@link ToolCallData}: resolves the JSON arguments against the
 * workflow scope (so {@code ${var}} references work inside the JSON), runs the
 * named tool through the registry and stores the tool's text result as the
 * step result.
 */
public class ToolCallStep extends BaseStepInstance<ToolCallData> {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public void execute() {
        ToolCallData cfg = getConfig();
        if (cfg.getTool() == null || cfg.getTool().isBlank()) {
            throw new IllegalArgumentException("tool-call step '" + cfg.getName() + "': no tool configured");
        }

        // A workflow from a project may only use what the agent that started it has.
        CallerLimits limits = getWorkflowContext() == null ? null
                : getWorkflowContext().getAttribute(CallerLimits.class);
        ai.mindconnect.agent.tool.AgentTool binding = limits == null ? null
                : limits.checkTool(cfg.getName(), cfg.getTool().trim());

        Map<String, Object> arguments = parseArguments(cfg);

        logDebug("calling tool '%s'", cfg.getTool());
        // On whose behalf: the host puts the caller's scope on the run (an agent
        // tool, a chat upload); a run started for nobody in particular has none.
        ToolCallScope scope = getWorkflowContext() == null ? null
                : getWorkflowContext().getAttribute(ToolCallScope.class);
        String result = binding != null
                ? ToolInvokers.require().call(binding, arguments, scope)
                : ToolInvokers.require().call(cfg.getTool().trim(), arguments, scope);
        // Tools report failure as text by convention ("Error: …"). A failed
        // tool must FAIL the step — a workflow that reports success while its
        // tool errored is a silent lie (ingestion once "succeeded" with zero
        // chunks this way). Opt out per step via failOnError=false.
        if (cfg.effectiveFailOnError() && result != null && result.trim().startsWith("Error:")) {
            String detail = result.trim();
            if (detail.length() > 300) detail = detail.substring(0, 300) + "…";
            throw new IllegalStateException("tool-call step '" + cfg.getName() + "': tool '"
                    + cfg.getTool().trim() + "' failed — " + detail);
        }
        setResult(result);
    }

    /**
     * Resolves every string in a nested map/list on its own. A string that is
     * exactly one {@code ${var}} yields the variable's value unflattened.
     */
    private Object resolveDeep(Object value) {
        if (value instanceof String s) {
            java.util.regex.Matcher single = SINGLE_VAR.matcher(s.trim());
            if (single.matches()) {
                return getVariableScope().getVariableValue(single.group(1));
            }
            return resolveExpression(s);
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new java.util.LinkedHashMap<>();
            map.forEach((k, v) -> out.put(String.valueOf(k), resolveDeep(v)));
            return out;
        }
        if (value instanceof java.util.List<?> list) {
            return list.stream().map(this::resolveDeep).toList();
        }
        return value;
    }

    /** Matches an arguments value that is exactly one variable reference, e.g. {@code ${writeArgs}}. */
    private static final java.util.regex.Pattern SINGLE_VAR =
            java.util.regex.Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_]*)}");

    private Map<String, Object> parseArguments(ToolCallData cfg) {
        if (cfg.getArgumentValues() != null) {
            @SuppressWarnings("unchecked")
            Map<String, Object> resolved = (Map<String, Object>) resolveDeep(cfg.getArgumentValues());
            return resolved;
        }
        String raw = cfg.getArguments();
        if (raw == null || raw.isBlank()) {
            return Map.of();
        }
        // "${args}" naming a Map variable (built by a code step) is passed
        // through as-is — the safe route for values that would break JSON
        // interpolation (quotes, newlines in file contents). The scope is
        // consulted directly because the string resolver flattens objects.
        java.util.regex.Matcher singleVar = SINGLE_VAR.matcher(raw.trim());
        if (singleVar.matches()) {
            Object value = getVariableScope().getVariableValue(singleVar.group(1));
            if (value instanceof Map<?, ?> map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> typed = (Map<String, Object>) map;
                return typed;
            }
        }
        Object resolved = resolveExpression(raw);
        if (resolved == null) {
            return Map.of();
        }
        if (resolved instanceof Map<?, ?> map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> typed = (Map<String, Object>) map;
            return typed;
        }
        String json = resolved.toString();
        if (json.isBlank()) {
            return Map.of();
        }
        try {
            return MAPPER.readValue(json, new TypeReference<Map<String, Object>>() { });
        } catch (Exception e) {
            throw new IllegalArgumentException(
                    "tool-call step '" + cfg.getName() + "': arguments are not a JSON object — "
                    + e.getMessage(), e);
        }
    }
}
