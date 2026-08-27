package ai.mindconnect.workflow.jackson;

import ai.mindconnect.workflow.domain.WorkflowData;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * High-level serializer / deserializer for {@link WorkflowData} definitions.
 *
 * <p>This is the primary API for persisting and loading workflow definitions
 * when using the Jackson integration module.
 *
 * <p>Example:
 * <pre>{@code
 * JacksonWorkflowSerializer serializer = new JacksonWorkflowSerializer(
 *         WorkflowObjectMapperFactory.create());
 *
 * // Deserialize
 * WorkflowData wd = serializer.read(path);
 *
 * // Serialize
 * String json = serializer.write(wd);
 * }</pre>
 */
@RequiredArgsConstructor
public class JacksonWorkflowSerializer {

    private final ObjectMapper mapper;

    // -----------------------------------------------------------------------
    // Read
    // -----------------------------------------------------------------------

    /** Deserializes a {@link WorkflowData} from a JSON string. */
    @SneakyThrows
    public WorkflowData read(String json) {
        return mapper.readValue(json, WorkflowData.class);
    }

    /** Deserializes a {@link WorkflowData} from a file path. */
    @SneakyThrows
    public WorkflowData read(Path path) {
        return mapper.readValue(path.toFile(), WorkflowData.class);
    }

    /** Deserializes a {@link WorkflowData} from an {@link InputStream}. */
    @SneakyThrows
    public WorkflowData read(InputStream is) {
        return mapper.readValue(is, WorkflowData.class);
    }

    /**
     * Reads a {@link WorkflowData} from a classpath resource.
     *
     * @param resourcePath classpath-relative path, e.g. {@code /workflows/my_flow.json}
     */
    @SneakyThrows
    public WorkflowData readFromClasspath(String resourcePath) {
        try (InputStream is = JacksonWorkflowSerializer.class.getResourceAsStream(resourcePath)) {
            if (is == null) {
                throw new IllegalArgumentException("Classpath resource not found: " + resourcePath);
            }
            return mapper.readValue(is, WorkflowData.class);
        }
    }

    // -----------------------------------------------------------------------
    // Write
    // -----------------------------------------------------------------------

    /** Serializes a {@link WorkflowData} to a pretty-printed JSON string. */
    @SneakyThrows
    public String write(WorkflowData workflowData) {
        return mapper.writeValueAsString(workflowData);
    }

    /** Serializes a {@link WorkflowData} to a file, creating parent dirs as needed. */
    @SneakyThrows
    public void write(WorkflowData workflowData, Path path) {
        Files.createDirectories(path.getParent());
        String json = mapper.writeValueAsString(workflowData);
        Files.writeString(path, json, StandardCharsets.UTF_8);
    }
}
