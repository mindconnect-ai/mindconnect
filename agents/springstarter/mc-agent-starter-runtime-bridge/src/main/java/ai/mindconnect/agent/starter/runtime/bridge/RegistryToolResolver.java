package ai.mindconnect.agent.starter.runtime.bridge;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import ai.mindconnect.agent.AgentId;
import ai.mindconnect.agent.SessionId;
import ai.mindconnect.agent.UserId;
import ai.mindconnect.agent.runtime.api.domain.AgentDefinition;
import ai.mindconnect.agent.runtime.api.domain.Session;
import ai.mindconnect.agent.runtime.api.domain.ToolBinding;
import ai.mindconnect.agent.runtime.api.domain.ToolCall;
import ai.mindconnect.agent.runtime.api.domain.ToolDefinition;
import ai.mindconnect.agent.runtime.api.domain.ToolOutput;
import ai.mindconnect.agent.runtime.api.port.out.Tool;
import ai.mindconnect.agent.runtime.api.port.out.ToolResolver;
import ai.mindconnect.agent.runtime.capabilities.permission.PermissionPlugin;
import ai.mindconnect.agent.tool.AgentTool;
import ai.mindconnect.agent.tool.AgentToolId;
import ai.mindconnect.agent.tool.ToolCallScope;
import ai.mindconnect.agent.tool.ToolRegistry;

/**
 * The tools of the earlier tool SPI as tools of this runtime: every tool
 * module the application has works without being touched. The registry
 * resolves a tool for one agent's entry — its fixed parameters, its own
 * description — and for the user and session of the call, so that is what
 * it is asked with, built from the agent's tool entry and the session.
 */
final class RegistryToolResolver implements ToolResolver {

    /** Metadata of a tool entry: the entry's id in the stored agent definition. */
    static final String TOOL_ID = "agentToolId";
    /** Metadata of a tool entry: the parameters this agent fixed in advance. */
    static final String OVERRIDES = "overrides";
    /** Metadata of a tool entry: the description this agent gives the tool. */
    static final String DESCRIPTION = "description";
    /** Metadata of a tool entry: how long a result may be for this agent. */
    static final String MAX_RESULT_CHARS = "maxResultChars";

    /** Metadata of a session: the directory its tools work in. */
    static final String WORKING_DIRECTORY = "workingDir";
    /** Metadata of a session: further directories its tools may reach. */
    static final String ADDITIONAL_DIRECTORIES = "additionalDirs";

    private final ToolRegistry registry;

    RegistryToolResolver(ToolRegistry registry) {
        this.registry = registry;
    }

    @Override
    public List<ToolDefinition> tools(AgentDefinition agent, Session session) {
        ToolCallScope scope = scope(agent, session);
        return agent.tools().stream()
                .filter(ToolBinding::enabled)
                .map(binding -> registry.resolve(agentTool(binding), scope))
                .flatMap(Optional::stream)
                .map(RegistryToolResolver::definition)
                .toList();
    }

    @Override
    public Optional<Tool> resolve(String toolName, AgentDefinition agent, Session session) {
        ToolCallScope scope = scope(agent, session);
        return agent.binding(toolName)
                .flatMap(binding -> registry.resolve(agentTool(binding), scope))
                .map(tool -> new Tool() {
                    @Override
                    public ToolDefinition definition() {
                        return RegistryToolResolver.definition(tool);
                    }

                    @Override
                    public ToolOutput execute(ToolCall call, Session in) {
                        try {
                            // The earlier tools ask the thread who is calling.
                            return ToolOutput.ok(scope.runWith(() -> tool.execute(call.arguments())));
                        } catch (RuntimeException e) {
                            return ToolOutput.failed("Error: " + e.getMessage());
                        }
                    }
                });
    }

    private static ToolDefinition definition(ai.mindconnect.agent.tool.Tool tool) {
        return new ToolDefinition(tool.name(), tool.description(), tool.parametersSchema());
    }

    @SuppressWarnings("unchecked")
    static AgentTool agentTool(ToolBinding binding) {
        Map<String, Object> metadata = binding.metadata();
        return new AgentTool(
                metadata.get(TOOL_ID) instanceof String id ? AgentToolId.of(id) : AgentToolId.random(),
                binding.name(),
                (String) metadata.get(DESCRIPTION),
                metadata.get(OVERRIDES) instanceof Map<?, ?> overrides ? (Map<String, Object>) overrides : Map.of(),
                binding.enabled(),
                binding.deferred(),
                Boolean.TRUE.equals(metadata.get(PermissionPlugin.NEEDS_APPROVAL)),
                metadata.get(MAX_RESULT_CHARS) instanceof Number limit ? limit.intValue() : null);
    }

    private static ToolCallScope scope(AgentDefinition agent, Session session) {
        SessionId sessionId = SessionId.of(session.id().value());
        return new ToolCallScope(
                UserId.of(session.userId().value()),
                sessionId,
                agent.metadata().get(AgentDefinitionMapper.AGENT_ID) instanceof String id ? AgentId.of(id) : null,
                sessionId,
                session.metadata().get(WORKING_DIRECTORY) instanceof String directory ? directory : null,
                session.metadata().get(ADDITIONAL_DIRECTORIES) instanceof List<?> more
                        ? more.stream().map(String::valueOf).toList() : List.of());
    }
}
