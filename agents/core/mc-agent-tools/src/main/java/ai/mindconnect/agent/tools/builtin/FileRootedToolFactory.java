package ai.mindconnect.agent.tools.builtin;

import ai.mindconnect.agent.tool.ToolEnvironment;
import ai.mindconnect.agent.tool.ToolFactory;

/**
 * Shared base for factories that produce a tool rooted at a base directory
 * (filesystem and document-reading tools). Captures only the default base dir
 * from the environment.
 */
abstract class FileRootedToolFactory implements ToolFactory {
    protected String defaultBaseDir;

    @Override
    public void bind(ToolEnvironment env) {
        this.defaultBaseDir = env.getString(BaseDirs.DEFAULT_BASE_DIR_KEY).orElse(null);
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
