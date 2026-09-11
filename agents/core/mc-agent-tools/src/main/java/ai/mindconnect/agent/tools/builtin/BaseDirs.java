package ai.mindconnect.agent.tools.builtin;

import ai.mindconnect.agent.tool.AgentTool;
import ai.mindconnect.agent.tool.FileRoots;
import ai.mindconnect.agent.tool.ToolCallScope;

/**
 * Resolves the working base directory for a file-rooted tool. The session's
 * working directory wins — it is where the user is, chosen for this very
 * conversation; then the tool's own {@code baseDir} override, then the
 * runtime-supplied default, finally {@code user.home}.
 */
final class BaseDirs {
    private BaseDirs() {}

    /** Key used by file-rooted tool factories to look up the default base dir in the environment. */
    static final String DEFAULT_BASE_DIR_KEY = "defaultBaseDir";

    /**
     * Where a file-rooted tool may go in this scope: the session's working
     * directory as the base — else the tool's override, the default, the
     * home — and the session's additional directories beside it.
     */
    static FileRoots roots(ToolCallScope scope, AgentTool tool, String defaultBaseDir) {
        String fallback = resolve(tool, defaultBaseDir);
        return scope == null ? FileRoots.of(java.nio.file.Path.of(fallback)) : scope.fileRoots(fallback);
    }

    static String resolve(ToolCallScope scope, AgentTool tool, String defaultBaseDir) {
        if (scope != null && scope.hasWorkingDir()) return scope.workingDir();
        return resolve(tool, defaultBaseDir);
    }

    /** Without a session: the tool's override, the default, the home. */
    static String resolve(AgentTool tool, String defaultBaseDir) {
        Object configured = tool.overrides().get("baseDir");
        if (configured instanceof String s && !s.isBlank()) return s;
        if (defaultBaseDir != null && !defaultBaseDir.isBlank()) return defaultBaseDir;
        return System.getProperty("user.home");
    }
}
