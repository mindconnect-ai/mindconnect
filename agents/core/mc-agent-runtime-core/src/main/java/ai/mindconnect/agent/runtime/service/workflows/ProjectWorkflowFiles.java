package ai.mindconnect.agent.runtime.service.workflows;

import ai.mindconnect.agent.tool.AgentTool;
import ai.mindconnect.agent.tool.AgentToolId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Where a project keeps its workflows — {@code .mindconnect/workflows/} in the
 * session's working directory, beside {@code agents/} and {@code skills/} —
 * and which files there are workflows. Only the files: reading them is the
 * workflow module's business, and this class is what the runtime needs to
 * decide whether an agent gets {@link #TOOL} at all.
 *
 * <p>Two layouts, like skills:
 *
 * <pre>
 * .mindconnect/workflows/release.yaml             ← a single file
 * .mindconnect/workflows/report/workflow.yaml     ← a directory, for files the workflow reads
 * </pre>
 *
 * The workflow's name is the file's name without {@code .yaml}/{@code .yml},
 * or the directory's name.
 */
public final class ProjectWorkflowFiles {

    private static final Logger log = LoggerFactory.getLogger(ProjectWorkflowFiles.class);

    /** Where a project keeps them, relative to the working directory. */
    public static final String DIR = ".mindconnect/workflows";

    /** The file a workflow directory is known by. */
    public static final String WORKFLOW_FILE = "workflow.yaml";

    /** The tool an agent runs a project workflow with; offered only when the project has one. */
    public static final String TOOL = "run_workflow";

    /**
     * The tool binding's overrides: the caller's tools, each as the binding
     * it has them with ({@link #callerTool}), handed to the tool the way
     * {@code tool_search} gets its search space, so the factory needs no
     * definition lookup.
     */
    public static final String CALLER_TOOLS = "callerTools";
    /** The agents the caller may call. */
    public static final String CALLER_AGENTS = "callerAgents";

    private ProjectWorkflowFiles() {}

    /**
     * One of the caller's tools as the {@link #CALLER_TOOLS} list carries it:
     * a plain map, since overrides are data — its name, the binding's
     * overrides (pins, an alias, container settings), whether it asks for an
     * approval and its result cap. {@link #fromCallerTool} makes it a binding
     * again.
     */
    public static Map<String, Object> callerTool(AgentTool tool) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("name", tool.name());
        map.put("overrides", tool.overrides());
        map.put("needsApproval", tool.needsApproval());
        if (tool.maxResultChars() != null) map.put("maxResultChars", tool.maxResultChars());
        return map;
    }

    /** The binding a {@link #callerTool} map describes; {@code null} for anything else. */
    @SuppressWarnings("unchecked")
    public static AgentTool fromCallerTool(Object raw) {
        if (!(raw instanceof Map<?, ?> map) || !(map.get("name") instanceof String name) || name.isBlank()) {
            return null;
        }
        Map<String, Object> overrides = map.get("overrides") instanceof Map<?, ?> o
                ? (Map<String, Object>) o : Map.of();
        Integer maxResultChars = map.get("maxResultChars") instanceof Number n ? n.intValue() : null;
        return new AgentTool(AgentToolId.random(), name, null, overrides, true, false,
                Boolean.TRUE.equals(map.get("needsApproval")), maxResultChars);
    }

    /** A workflow file and the name it goes by. */
    public record WorkflowFile(String name, Path file) {}

    /** Whether the project in {@code workingDir} defines any workflow. */
    public static boolean present(String workingDir) {
        return !list(workingDir).isEmpty();
    }

    /** Every workflow file of the project, in name order; empty when there are none. */
    public static List<WorkflowFile> list(String workingDir) {
        Path dir = dir(workingDir);
        if (dir == null || !Files.isDirectory(dir)) return List.of();
        List<WorkflowFile> files = new ArrayList<>();
        try (Stream<Path> entries = Files.list(dir)) {
            entries.sorted().forEach(entry -> {
                String fileName = entry.getFileName().toString();
                if (Files.isDirectory(entry)) {
                    Path inside = entry.resolve(WORKFLOW_FILE);
                    if (Files.isRegularFile(inside)) files.add(new WorkflowFile(fileName, inside));
                    return;
                }
                String lower = fileName.toLowerCase(Locale.ROOT);
                if (!Files.isRegularFile(entry)) return;
                if (lower.endsWith(".yaml")) {
                    files.add(new WorkflowFile(fileName.substring(0, fileName.length() - 5), entry));
                } else if (lower.endsWith(".yml")) {
                    files.add(new WorkflowFile(fileName.substring(0, fileName.length() - 4), entry));
                }
            });
        } catch (IOException | RuntimeException e) {
            log.debug("Project workflows in {} could not be listed: {}", dir, e.toString());
            return List.of();
        }
        return List.copyOf(files);
    }

    /** {@code <workingDir>/.mindconnect/workflows}, or {@code null} without a working directory. */
    public static Path dir(String workingDir) {
        if (workingDir == null || workingDir.isBlank()) return null;
        try {
            return Path.of(workingDir).resolve(DIR);
        } catch (InvalidPathException e) {
            return null;
        }
    }
}
