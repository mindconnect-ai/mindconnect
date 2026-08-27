package ai.mindconnect.agent.tools.workflow.step;

import ai.mindconnect.workflow.execution.BaseStepInstance;

/**
 * Executes an {@link AgentCallData}: resolves the message against the workflow
 * scope, runs one full chat turn on the named agent (fresh session — tools,
 * sub-agents and reviewers included) and stores the agent's final answer as
 * the step result.
 */
public class AgentCallStep extends BaseStepInstance<AgentCallData> {

    @Override
    public void execute() {
        AgentCallData cfg = getConfig();
        String agentName;
        if (cfg.getAgentSpec() != null && !cfg.getAgentSpec().isEmpty()) {
            // Inline agent: resolve ${...} in every string of the spec, then
            // upsert by name — idempotent, a ForEach reuses one definition.
            Object resolved = resolveDeep(cfg.getAgentSpec());
            @SuppressWarnings("unchecked")
            java.util.Map<String, Object> spec = (java.util.Map<String, Object>) resolved;
            agentName = AgentInvokers.require().upsertAgent(spec);
        } else if (cfg.getAgent() != null && !cfg.getAgent().isBlank()) {
            agentName = cfg.getAgent().trim();
        } else {
            throw new IllegalArgumentException("agent-call step '" + cfg.getName()
                    + "': neither agent nor agentSpec configured");
        }
        String message = resolveString(cfg.getMessage());
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("agent-call step '" + cfg.getName() + "': no message configured");
        }

        logDebug("calling agent '%s'", agentName);
        String answer = AgentInvokers.require().call(agentName, message);
        setResult(answer);
    }

    /** Expression-resolves every string value in a nested map/list structure. */
    private Object resolveDeep(Object value) {
        if (value instanceof String s) {
            return resolveExpression(s);
        }
        if (value instanceof java.util.Map<?, ?> map) {
            java.util.Map<String, Object> out = new java.util.LinkedHashMap<>();
            map.forEach((k, v) -> out.put(String.valueOf(k), resolveDeep(v)));
            return out;
        }
        if (value instanceof java.util.List<?> list) {
            return list.stream().map(this::resolveDeep).toList();
        }
        return value;
    }

    private String resolveString(String value) {
        if (value == null) return null;
        Object resolved = resolveExpression(value);
        return resolved == null ? null : resolved.toString();
    }
}
