package ai.mindconnect.agent.tools.builtin;

import ai.mindconnect.agent.tool.AgentTool;

/**
 * Resolves the working base directory for a file-rooted tool: the tool's own
 * {@code baseDir} config wins, otherwise the runtime-supplied default, finally
 * {@code user.home}.
 */
final class BaseDirs {
    private BaseDirs() {}

    /** Key used by file-rooted tool factories to look up the default base dir in the environment. */
    static final String DEFAULT_BASE_DIR_KEY = "defaultBaseDir";

    static String resolve(AgentTool tool, String defaultBaseDir) {
        Object configured = tool.overrides().get("baseDir");
        if (configured instanceof String s && !s.isBlank()) return s;
        if (defaultBaseDir != null && !defaultBaseDir.isBlank()) return defaultBaseDir;
        return System.getProperty("user.home");
    }
}
