package ai.mindconnect.agent.tools.builtin;

import ai.mindconnect.agent.tools.workspace.WorkspaceScope;
import ai.mindconnect.agent.tool.Tool;
import ai.mindconnect.agent.tools.workspace.WorkspaceStore;

import java.util.Map;
import java.util.UUID;

/**
 * Reads a file from one of the three workspace scopes.
 *
 * Parameters:
 *   scope    — "session" | "agent" | "user"
 *   filename — e.g. "notes.md", "summary.md"
 */
public class WorkspaceReadTool implements Tool {

    private final WorkspaceStore workspaceStore;
    private final UUID agentId;
    private final String userId;
    private final UUID sessionId;

    public WorkspaceReadTool(WorkspaceStore workspaceStore, UUID agentId, String userId, UUID sessionId) {
        this.workspaceStore = workspaceStore;
        this.agentId = agentId;
        this.userId = userId;
        this.sessionId = sessionId;
    }

    @Override
    public String name() { return "workspace_read"; }

    @Override
    public String description() {
        return "Reads a file from the agent workspace. " +
               "scope: 'session' (this session only), 'agent' (persists across sessions with this user), " +
               "'user' (shared across all agents for this user, read-only). " +
               "filename: e.g. 'notes.md', 'summary.md', 'todo.md'.";
    }

    @Override
    public Map<String, Object> parametersSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "scope", Map.of(
                                "type", "string",
                                "enum", new String[]{"session", "agent", "user"},
                                "description", "Which workspace scope to read from"
                        ),
                        "filename", Map.of(
                                "type", "string",
                                "description", "Name of the file to read, e.g. notes.md"
                        )
                ),
                "required", new String[]{"scope", "filename"}
        );
    }

    @Override
    public String execute(Map<String, Object> arguments) {
        String scopeName = (String) arguments.get("scope");
        String filename = (String) arguments.get("filename");

        if (scopeName == null || scopeName.isBlank()) return "Error: scope is required";
        if (filename == null || filename.isBlank()) return "Error: filename is required";

        WorkspaceScope scope = resolveScope(scopeName);
        if (scope == null) return "Error: unknown scope '" + scopeName + "'. Use: session, agent, user";

        return workspaceStore.read(scope, filename)
                .map(content -> content.isEmpty() ? "(empty file)" : content)
                .orElse("File '" + filename + "' not found in " + scopeName + " workspace.");
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
