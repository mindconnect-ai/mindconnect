package ai.mindconnect.agent.tools.document;

import ai.mindconnect.agent.tool.AgentTool;
import ai.mindconnect.agent.tool.ToolEnvironment;
import ai.mindconnect.agent.tool.ToolFactory;

/**
 * Base-dir lookup for document tools. Mirrors the equivalent helper in the
 * builtin-tools module so each module remains independently deployable.
 */
final class DocBaseDirs {
    private DocBaseDirs() {}

    static final String DEFAULT_BASE_DIR_KEY = "defaultBaseDir";

    static String resolve(AgentTool tool, String defaultBaseDir) {
        Object configured = tool.overrides().get("baseDir");
        if (configured instanceof String s && !s.isBlank()) return s;
        if (defaultBaseDir != null && !defaultBaseDir.isBlank()) return defaultBaseDir;
        return System.getProperty("user.home");
    }

    static abstract class FileRooted implements ToolFactory {
        protected String defaultBaseDir;
        @Override public void bind(ToolEnvironment env) {
            this.defaultBaseDir = env.getString(DEFAULT_BASE_DIR_KEY).orElse(null);
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
