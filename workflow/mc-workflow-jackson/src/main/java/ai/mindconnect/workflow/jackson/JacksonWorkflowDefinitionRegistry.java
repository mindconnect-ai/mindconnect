package ai.mindconnect.workflow.jackson;

import ai.mindconnect.workflow.domain.WorkflowData;
import ai.mindconnect.workflow.execution.WorkflowDefinitionRegistry;
import ai.mindconnect.workflow.util.ResourceUtil;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A {@link WorkflowDefinitionRegistry} that lazily loads workflow definitions
 * from JSON files on the classpath.
 *
 * <p>Workflows are stored under a configurable base directory (default:
 * {@code /workflows}) as {@code <workflowId>.json}.  Definitions loaded from
 * the classpath are cached so each file is only parsed once.
 *
 * <p>Programmatically registered definitions (via {@link #putWorkflowDefinition})
 * take precedence over classpath files.
 *
 * <p>Example classpath layout:
 * <pre>
 * src/main/resources/
 *   workflows/
 *     my_import_flow.json
 *     my_query_flow.json
 * </pre>
 *
 * Then: {@code registry.getWorkflowDefinition("my_import_flow")} loads and
 * caches {@code /workflows/my_import_flow.json}.
 */
public class JacksonWorkflowDefinitionRegistry implements WorkflowDefinitionRegistry {

    private final Map<String, WorkflowData> cache = new LinkedHashMap<>();
    private final JacksonWorkflowSerializer serializer;
    private final String baseDir;

    /**
     * Creates a registry that scans the default {@code /workflows} classpath directory.
     */
    public JacksonWorkflowDefinitionRegistry(JacksonWorkflowSerializer serializer) {
        this(serializer, "/workflows");
    }

    /**
     * Creates a registry that scans the given classpath directory.
     *
     * @param serializer the serializer used to parse JSON files
     * @param baseDir    classpath-relative directory, e.g. {@code /workflows}
     */
    public JacksonWorkflowDefinitionRegistry(JacksonWorkflowSerializer serializer, String baseDir) {
        this.serializer = serializer;
        this.baseDir = baseDir.endsWith("/") ? baseDir.substring(0, baseDir.length() - 1) : baseDir;
    }

    // -----------------------------------------------------------------------
    // WorkflowDefinitionRegistry
    // -----------------------------------------------------------------------

    @Override
    public WorkflowData getWorkflowDefinition(String workflowId) {
        WorkflowData cached = cache.get(workflowId);
        if (cached != null) return cached;

        String resourcePath = baseDir + "/" + workflowId + ".json";
        try {
            String content = ResourceUtil.readFromClasspath(resourcePath);
            WorkflowData wd = serializer.read(content);
            cache.put(workflowId, wd);
            return wd;
        } catch (IllegalArgumentException e) {
            // Resource not found — return null so callers can decide how to handle it
            return null;
        } catch (RuntimeException e) {
            throw new RuntimeException(
                    "Failed to load workflow '" + workflowId + "' from " + resourcePath + ": " + e.getMessage(), e);
        }
    }

    @Override
    public void putWorkflowDefinition(String id, WorkflowData workflowData) {
        cache.put(id, workflowData);
    }
}
