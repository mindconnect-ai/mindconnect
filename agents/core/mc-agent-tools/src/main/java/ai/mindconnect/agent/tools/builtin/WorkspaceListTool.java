package ai.mindconnect.agent.tools.builtin;

import ai.mindconnect.agent.tools.workspace.WorkspaceScope;
import ai.mindconnect.agent.tool.Tool;
import ai.mindconnect.agent.tools.workspace.WorkspaceStore;

import java.util.Map;
import java.util.UUID;

/**
 * Lists the files available in one of the agent's workspace scopes.
 *
 * Parameters:
 *   scope — "session" | "agent" | "user"
 */
public class WorkspaceListTool implements Tool {

    private final WorkspaceStore workspaceStore;
    private final UUID agentId;
    private final String userId;
    private final UUID sessionId;

    public WorkspaceListTool(WorkspaceStore workspaceStore, UUID agentId, String userId, UUID sessionId) {
        this.workspaceStore = workspaceStore;
        this.agentId = agentId;
        this.userId = userId;
        this.sessionId = sessionId;
    }

    @Override
    public String name() { return "workspace_list"; }

    @Override
    public String description() {
        return "Lists files available in the given workspace scope. " +
               "scope: 'session' | 'agent' | 'user'.";
    }

    @Override
    public Map<String, Object> parametersSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "scope", Map.of(
                                "type", "string",
                                "enum", new String[]{"session", "agent", "user"},
                                "description", "Which workspace scope to list"
                        )
                ),
                "required", new String[]{"scope"}
        );
    }

    @Override
    public String execute(Map<String, Object> arguments) {
        String scopeName = (String) arguments.get("scope");
        if (scopeName == null || scopeName.isBlank())
            return "Error: scope is required";

        WorkspaceScope scope = resolveScope(scopeName);
        if (scope == null)
            return "Error: unknown scope '" + scopeName + "'. Use: session, agent, user";

        java.util.List<String> files = workspaceStore.list(scope);
        if (files.isEmpty())
            return "No files in " + scopeName + " workspace.";

        return scopeName + " workspace files:\n" +
                files.stream().map(f -> "  - " + f).collect(java.util.stream.Collectors.joining("\n"));
    }

    private WorkspaceScope resolveScope(String scopeName) {
        return switch (scopeName.toLowerCase()) {
            case "session" -> WorkspaceScope.session(agentId, userId, sessionId);
            case "agent"   -> WorkspaceScope.agentUser(agentId, userId);
            case "user"    -> WorkspaceScope.user(userId);
            default        -> null;
        };
    }
}
