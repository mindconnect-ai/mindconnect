package ai.mindconnect.agent.tools.document;

import ai.mindconnect.agent.tool.AgentTool;
import ai.mindconnect.agent.tool.ToolEnvironment;
import ai.mindconnect.agent.tool.ToolFactory;
import ai.mindconnect.agent.tool.workspace.WorkspaceFiles;
import ai.mindconnect.agent.tool.workspace.WorkspaceProvider;

/**
 * Base-dir lookup for document tools. Mirrors the equivalent helper in the
 * builtin-tools module so each module remains independently deployable.
 */
final class DocBaseDirs {
    private DocBaseDirs() {}

    static final String DEFAULT_BASE_DIR_KEY = "defaultBaseDir";

    /**
     * Where a document tool may go in this scope: the session's working
     * directory as the base — else the tool's override, the default, the
     * home — and the session's additional directories beside it.
     */
    static ai.mindconnect.agent.tool.FileRoots roots(ai.mindconnect.agent.tool.ToolCallScope scope,
                                                    AgentTool tool, String defaultBaseDir) {
        String fallback = resolve(tool, defaultBaseDir);
        return scope == null
                ? ai.mindconnect.agent.tool.FileRoots.of(java.nio.file.Path.of(fallback))
                : scope.fileRoots(fallback);
    }

    /** The session's working directory first, then the tool's override, the default, the home. */
    static String resolve(ai.mindconnect.agent.tool.ToolCallScope scope, AgentTool tool, String defaultBaseDir) {
        if (scope != null && scope.hasWorkingDir()) return scope.workingDir();
        return resolve(tool, defaultBaseDir);
    }

    static String resolve(AgentTool tool, String defaultBaseDir) {
        Object configured = tool.overrides().get("baseDir");
        if (configured instanceof String s && !s.isBlank()) return s;
        if (defaultBaseDir != null && !defaultBaseDir.isBlank()) return defaultBaseDir;
        return System.getProperty("user.home");
    }

    static abstract class FileRooted implements ToolFactory {
        protected String defaultBaseDir;
        /** Where the files live when not on this machine; empty for the local roots. */
        protected java.util.Optional<WorkspaceProvider> workspaces = java.util.Optional.empty();

        @Override public void bind(ToolEnvironment env) {
            this.defaultBaseDir = env.getString(DEFAULT_BASE_DIR_KEY).orElse(null);
            this.workspaces = env.get(WorkspaceProvider.class);
        }

        /** The provider's workspace when one is bound, the session's roots here otherwise. */
        protected WorkspaceFiles files(AgentTool agentTool, ai.mindconnect.agent.tool.ToolCallScope scope) {
            return WorkspaceProvider.localOrProvided(workspaces, scope, agentTool, roots(scope, agentTool, defaultBaseDir));
        }

        @Override public java.util.Map<String, Object> overridesSchema() {
            java.util.Map<String, Object> baseDir = new java.util.LinkedHashMap<>();
            baseDir.put("type", "string");
            if (defaultBaseDir != null) {
                baseDir.put("default", defaultBaseDir);
            }
            baseDir.put("description", "Base directory this tool operates in, overriding the runtime default.");
            return java.util.Map.of("type", "object", "properties", java.util.Map.of("baseDir", baseDir));
        }
    }
}
