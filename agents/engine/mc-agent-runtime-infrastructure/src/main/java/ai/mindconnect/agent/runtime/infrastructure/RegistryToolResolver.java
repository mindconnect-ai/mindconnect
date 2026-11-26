package ai.mindconnect.agent.runtime.infrastructure;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import ai.mindconnect.agent.AgentId;
import ai.mindconnect.agent.SessionId;
import ai.mindconnect.agent.UserId;
import ai.mindconnect.agent.runtime.api.domain.AgentDefinition;
import ai.mindconnect.agent.runtime.api.domain.Session;
import ai.mindconnect.agent.management.api.ManagedAgent;
import ai.mindconnect.agent.management.tool.AgentToolBinding;
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
import ai.mindconnect.agent.tool.UserToolRoster;

/**
 * The tools of the earlier tool SPI as tools of this runtime: every tool
 * module the application has works without being touched. The registry
 * resolves a tool for one agent's entry — its fixed parameters, its own
 * description — and for the user and session of the call, so that is what
 * it is asked with, built from the agent's tool entry and the session.
 *
 * <p>In a session a user chats in, the tools that user keeps in their own
 * account are laid over the agent's: the ones they added are there, the ones
 * they switched off are not, and what they set for one holds. A sub-agent
 * keeps the tools its definition gives it — somebody chose them for a narrow
 * job.
 */
final class RegistryToolResolver implements ToolResolver {

    /** Metadata of a session: the directory its tools work in. */
    static final String WORKING_DIRECTORY = "workingDir";
    /** Metadata of a session: further directories its tools may reach. */
    static final String ADDITIONAL_DIRECTORIES = "additionalDirs";

    private final Supplier<ToolRegistry> registered;
    private final Supplier<UserToolRoster> userTools;

    RegistryToolResolver(Supplier<ToolRegistry> registered, Supplier<UserToolRoster> userTools) {
        this.registered = registered;
        this.userTools = userTools;
    }

    @Override
    public List<ToolDefinition> tools(AgentDefinition agent, Session session) {
        ToolCallScope scope = scope(agent, session);
        return bound(agent, session).stream()
                .map(tool -> registered.get().resolve(tool, scope))
                .flatMap(Optional::stream)
                .map(RegistryToolResolver::definition)
                .toList();
    }

    /**
     * The tools the session's user brought along that the agent does not
     * name itself: whoever puts the offer together adds these, since an
     * offer otherwise holds only what the agent names.
     */
    List<ToolDefinition> added(AgentDefinition agent, Session session) {
        ToolCallScope scope = scope(agent, session);
        return bound(agent, session).stream()
                .filter(tool -> !agent.uses(tool.name()))
                .map(tool -> registered.get().resolve(tool, scope))
                .flatMap(Optional::stream)
                .map(RegistryToolResolver::definition)
                .toList();
    }

    /** The tools of the session's user that ask before they run although the agent's entry does not. */
    List<String> asking(AgentDefinition agent, Session session) {
        return bound(agent, session).stream()
                .filter(AgentTool::needsApproval)
                .map(AgentTool::name)
                .toList();
    }

    /** What the agent binds, with the user's own laid over it in a session that user chats in. */
    private List<AgentTool> bound(AgentDefinition agent, Session session) {
        List<AgentTool> named = agent.tools().stream().map(RegistryToolResolver::agentTool).toList();
        if (session.parentId() != null || session.userId() == null) {
            return named;
        }
        return userTools.get().apply(session.userId(), agentId(agent), named).stream()
                .filter(AgentTool::enabled)
                .toList();
    }

    @Override
    public Optional<Tool> resolve(String toolName, AgentDefinition agent, Session session) {
        ToolCallScope scope = scope(agent, session);
        return bound(agent, session).stream().filter(tool -> tool.name().equals(toolName)).findFirst()
                .flatMap(bound -> registered.get().resolve(bound, scope))
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

    static AgentTool agentTool(ToolBinding binding) {
        return AgentToolBinding.agentTool(binding);
    }

    private static ToolCallScope scope(AgentDefinition agent, Session session) {
        SessionId sessionId = session.id();
        return new ToolCallScope(
                session.userId(),
                sessionId,
                agentId(agent),
                sessionId,
                session.metadata().get(WORKING_DIRECTORY) instanceof String directory ? directory : null,
                session.metadata().get(ADDITIONAL_DIRECTORIES) instanceof List<?> more
                        ? more.stream().map(String::valueOf).toList() : List.of());
    }

    private static AgentId agentId(AgentDefinition agent) {
        return agent.metadata().get(ManagedAgent.AGENT_ID) instanceof String id ? AgentId.of(id) : null;
    }
}
