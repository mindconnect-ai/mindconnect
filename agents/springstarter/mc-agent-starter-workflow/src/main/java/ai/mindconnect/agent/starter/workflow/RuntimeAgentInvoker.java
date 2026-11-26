package ai.mindconnect.agent.starter.workflow;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import ai.mindconnect.agent.SessionId;
import ai.mindconnect.agent.AgentId;
import ai.mindconnect.agent.management.api.AgentManagement;
import ai.mindconnect.agent.management.api.ManagedAgent;
import ai.mindconnect.agent.management.api.SessionAgent;
import ai.mindconnect.agent.management.api.SessionDirectories;
import ai.mindconnect.agent.management.api.SessionManagement;
import ai.mindconnect.agent.management.tool.AgentToolBinding;
import ai.mindconnect.agent.runtime.api.domain.AgentDefinition;
import ai.mindconnect.agent.runtime.api.domain.ModelSettings;
import ai.mindconnect.agent.runtime.api.domain.ToolBinding;
import ai.mindconnect.agent.UserId;
import ai.mindconnect.agent.sdk.AgentRuntime;
import ai.mindconnect.agent.tool.AgentTool;
import ai.mindconnect.agent.tool.AgentToolId;
import ai.mindconnect.agent.tools.workflow.WorkflowAgentCalls;
import ai.mindconnect.agent.tools.workflow.step.AgentInvoker;

/**
 * Lets an agent-call workflow step run a turn of the runtime in this
 * process: the named agent is asked in a session of the run's own, as the
 * user the workflow runs for, and its answer is the step's result.
 */
final class RuntimeAgentInvoker implements AgentInvoker {

    private static final int DEFAULT_MAX_ROUNDS = 10;

    private final AgentRuntime runtime;
    private final WorkflowAgentCalls calls;

    /** @param timeout how long a step waits for the agent's answer */
    RuntimeAgentInvoker(AgentRuntime runtime, Duration timeout) {
        this.runtime = runtime;
        this.calls = new WorkflowAgentCalls(
                name -> agents().findByName(name).map(agent -> agent.id()),
                (agent, user) -> SessionId.of(runtime.service(SessionManagement.class).open(
                        user, null, new SessionAgent.Ref(agent),
                        WorkflowAgentCalls.SESSION_TYPE, SessionDirectories.NONE).id().value()),
                (session, message) -> new RuntimeChatTurn(runtime,
                        session, message),
                timeout);
    }

    private AgentManagement agents() {
        return runtime.service(AgentManagement.class);
    }

    @Override
    public String call(String agentName, String message) {
        return calls.call(agentName, message);
    }

    /**
     * Creates the agent a step describes inline, or brings the stored one of
     * that name up to the description: what the spec leaves out stays as it
     * is, the tools are the ones it names.
     */
    @Override
    public String upsertAgent(Map<String, Object> spec) {
        String name = text(spec.get("name"));
        if (name == null) {
            throw new IllegalArgumentException("agentSpec needs a 'name'");
        }
        String instructions = text(spec.get("systemPrompt"));
        String description = text(spec.get("description"));
        String model = text(spec.get("llmConfigName"));
        List<ToolBinding> tools = tools(spec.get("tools"));

        Optional<ManagedAgent> stored = agents().findByName(name);
        if (stored.isEmpty()) {
            agents().create(ManagedAgent.of(new AgentDefinition(name, description == null ? "" : description,
                    instructions == null ? "" : instructions,
                    model == null ? ModelSettings.NONE : ModelSettings.ofModel(model), tools, DEFAULT_MAX_ROUNDS,
                    Map.of())));
        } else {
            agents().update(stored.get().id(), stored.get().version(), current -> {
                AgentDefinition before = current.definition();
                return current.withDefinition(new AgentDefinition(name,
                        description != null ? description : before.description(),
                        instructions != null ? instructions : before.instructions(),
                        model != null ? ModelSettings.ofModel(model) : before.modelSettings(), tools,
                        before.maxRounds(), before.metadata()));
            });
        }
        return name;
    }

    private static List<ToolBinding> tools(Object raw) {
        List<ToolBinding> tools = new ArrayList<>();
        if (raw instanceof List<?> listed) {
            for (Object entry : listed) {
                if (!(entry instanceof Map<?, ?> tool) || text(tool.get("name")) == null) {
                    continue;
                }
                Map<String, Object> overrides = new LinkedHashMap<>();
                if (tool.get("overrides") instanceof Map<?, ?> given) {
                    given.forEach((key, value) -> overrides.put(String.valueOf(key), value));
                }
                // Shortcut: "tool" may sit next to "name" instead of inside overrides.
                if (tool.get("tool") instanceof String alias && !alias.isBlank()) {
                    overrides.putIfAbsent("tool", alias);
                }
                tools.add(AgentToolBinding.of(new AgentTool(AgentToolId.random(), text(tool.get("name")),
                        text(tool.get("description")), overrides)));
            }
        }
        return tools;
    }

    private static String text(Object value) {
        return value instanceof String text && !text.isBlank() ? text : null;
    }
}
