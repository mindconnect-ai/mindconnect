package ai.mindconnect.agent.tools.builtin;

import ai.mindconnect.agent.tool.AgentTool;
import ai.mindconnect.agent.tool.ToolCallScope;
import ai.mindconnect.agent.tool.workspace.WorkspaceFiles;
import ai.mindconnect.agent.tool.ToolEnvironment;
import ai.mindconnect.agent.tool.ToolFactory;
import ai.mindconnect.agent.tool.workspace.WorkspaceProvider;

import java.util.Optional;

/**
 * Shared base for factories that produce a tool rooted at a base directory
 * (filesystem and document-reading tools). Captures only the default base dir
 * from the environment.
 */
abstract class FileRootedToolFactory implements ToolFactory {
    protected String defaultBaseDir;
    /** Where the files live when not on this machine; empty for the local roots. */
    protected Optional<WorkspaceProvider> workspaces = Optional.empty();

    @Override
    public void bind(ToolEnvironment env) {
        this.defaultBaseDir = env.getString(BaseDirs.DEFAULT_BASE_DIR_KEY).orElse(null);
        this.workspaces = env.get(WorkspaceProvider.class);
    }

    /** The files this call works on: the provider's workspace when one is bound, the session's roots here otherwise. */
    protected WorkspaceFiles files(AgentTool agentTool, ToolCallScope scope) {
        return WorkspaceProvider.localOrProvided(workspaces, scope, agentTool,
                BaseDirs.roots(scope, agentTool, defaultBaseDir));
    }

    @Override
    public java.util.Map<String, Object> overridesSchema() {
        java.util.Map<String, Object> baseDir = new java.util.LinkedHashMap<>();
        baseDir.put("type", "string");
        if (defaultBaseDir != null) {
            baseDir.put("default", defaultBaseDir);
        }
        baseDir.put("description", "Base directory this tool operates in, overriding the runtime default.");
        return java.util.Map.of("type", "object", "properties", java.util.Map.of("baseDir", baseDir));
    }
}
