package ai.mindconnect.agent.tools.builtin;

import ai.mindconnect.agent.tools.workspace.WorkspaceScope;
import ai.mindconnect.agent.tool.Tool;
import ai.mindconnect.agent.tools.workspace.WorkspaceStore;

import java.util.Map;
import java.util.UUID;

/**
 * Writes a file to one of the agent's workspace scopes.
 *
 * Parameters:
 *   scope    — "session" | "agent"  (user scope is read-only)
 *   filename — e.g. "notes.md", "todo.md"
 *   content  — the content to write
 */
public class WorkspaceWriteTool implements Tool {

    private final WorkspaceStore workspaceStore;
    private final UUID agentId;
    private final String userId;
    private final UUID sessionId;

    public WorkspaceWriteTool(WorkspaceStore workspaceStore, UUID agentId, String userId, UUID sessionId) {
        this.workspaceStore = workspaceStore;
        this.agentId = agentId;
        this.userId = userId;
        this.sessionId = sessionId;
    }

    @Override
    public String name() { return "workspace_write"; }

    @Override
    public String description() {
        return "Writes (or overwrites) a file in the agent workspace. " +
               "scope: 'session' (this session only, cleared on compress), " +
               "'agent' (persists across sessions with this user — use for notes, facts, preferences). " +
               "filename: e.g. 'notes.md', 'todo.md'. " +
               "Use 'agent' scope and 'notes.md' to remember important facts about the user.";
    }

    @Override
    public Map<String, Object> parametersSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "scope", Map.of(
                                "type", "string",
                                "enum", new String[]{"session", "agent"},
                                "description", "Which workspace scope to write to (user scope is read-only)"
                        ),
                        "filename", Map.of(
                                "type", "string",
                                "description", "Name of the file to write, e.g. notes.md"
                        ),
                        "content", Map.of(
                                "type", "string",
                                "description", "Content to write to the file"
                        )
                ),
                "required", new String[]{"scope", "filename", "content"}
        );
    }

    @Override
    public String execute(Map<String, Object> arguments) {
        String scopeName = (String) arguments.get("scope");
        String filename  = (String) arguments.get("filename");
        String content   = (String) arguments.get("content");

        if (scopeName == null || scopeName.isBlank()) return "Error: scope is required";
        if (filename == null || filename.isBlank())   return "Error: filename is required";
        if (content == null)                          return "Error: content is required";

        WorkspaceScope scope = resolveScope(scopeName);
        if (scope == null) return "Error: unknown scope '" + scopeName + "'. Use: session, agent";

        workspaceStore.write(scope, filename, content);
        return "Written " + content.length() + " chars to '" + filename + "' in " + scopeName + " workspace.";
    }

    private WorkspaceScope resolveScope(String scopeName) {
        return switch (scopeName.toLowerCase()) {
            case "session" -> WorkspaceScope.session(agentId, userId, sessionId);
            case "agent"   -> WorkspaceScope.agentUser(agentId, userId);
            default        -> null;
        };
    }
}
