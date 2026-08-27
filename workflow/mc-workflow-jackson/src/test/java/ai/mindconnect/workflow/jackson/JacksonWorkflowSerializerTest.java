package ai.mindconnect.workflow.jackson;

import ai.mindconnect.workflow.domain.AssignVariablesData;
import ai.mindconnect.workflow.domain.VariableAssignment;
import ai.mindconnect.workflow.domain.WorkflowData;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Tests for {@link JacksonWorkflowSerializer}.
 */
public class JacksonWorkflowSerializerTest {

    private final ObjectMapper mapper = WorkflowObjectMapperFactory.create();
    private final JacksonWorkflowSerializer serializer = new JacksonWorkflowSerializer(mapper);

    // -----------------------------------------------------------------------
    // Read from String
    // -----------------------------------------------------------------------

    @Test
    public void readFromJsonString() {
        WorkflowData source = buildSampleWorkflow("wf_read_string");
        String json = serializer.write(source);

        WorkflowData restored = serializer.read(json);

        Assertions.assertThat(restored.getName()).isEqualTo("wf_read_string");
        Assertions.assertThat(restored.getSteps()).hasSize(1);
        Assertions.assertThat(restored.getResultFrom()).isEqualTo("result");
    }

    // -----------------------------------------------------------------------
    // Read from Path
    // -----------------------------------------------------------------------

    @Test
    public void readFromPath(@TempDir Path tempDir) throws Exception {
        WorkflowData source = buildSampleWorkflow("wf_read_path");
        Path file = tempDir.resolve("wf_read_path.json");
        serializer.write(source, file);

        WorkflowData restored = serializer.read(file);

        Assertions.assertThat(restored.getName()).isEqualTo("wf_read_path");
        Assertions.assertThat(restored.getSteps()).hasSize(1);
    }

    // -----------------------------------------------------------------------
    // Read from classpath
    // -----------------------------------------------------------------------

    @Test
    public void readFromClasspath() {
        // Loads src/test/resources/workflows/wf_hello.json
        WorkflowData wd = serializer.readFromClasspath("/workflows/wf_hello.json");

        Assertions.assertThat(wd.getName()).isEqualTo("wf_hello");
        Assertions.assertThat(wd.getSteps()).hasSize(1);
        Assertions.assertThat(wd.getSteps().get(0)).isInstanceOf(AssignVariablesData.class);
        Assertions.assertThat(wd.getResultFrom()).isEqualTo("greeting");
    }

    @Test
    public void readFromClasspathMissingResourceThrows() {
        Assertions.assertThatThrownBy(() ->
                serializer.readFromClasspath("/workflows/does_not_exist.json"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Classpath resource not found");
    }

    // -----------------------------------------------------------------------
    // Write
    // -----------------------------------------------------------------------

    @Test
    public void writeToString() {
        WorkflowData wd = buildSampleWorkflow("wf_write_string");
        String json = serializer.write(wd);

        Assertions.assertThat(json).contains("\"@class\"");
        Assertions.assertThat(json).contains("wf_write_string");
        Assertions.assertThat(json).contains("AssignVariablesData");
    }

    @Test
    public void writeToPath(@TempDir Path tempDir) throws Exception {
        WorkflowData wd = buildSampleWorkflow("wf_write_path");
        Path file = tempDir.resolve("wf_write_path.json");
        serializer.write(wd, file);

        Assertions.assertThat(Files.exists(file)).isTrue();
        String content = Files.readString(file);
        Assertions.assertThat(content).contains("wf_write_path");
    }

    // -----------------------------------------------------------------------
    // Round-trip
    // -----------------------------------------------------------------------

    @Test
    public void roundTripPreservesAllFields() {
        WorkflowData source = buildSampleWorkflow("wf_roundtrip");
        source.setWorkflowType("import");
        source.addParam("inputParam");

        String json = serializer.write(source);
        WorkflowData restored = serializer.read(json);

        Assertions.assertThat(restored.getName()).isEqualTo("wf_roundtrip");
        Assertions.assertThat(restored.getWorkflowType()).isEqualTo("import");
        Assertions.assertThat(restored.paramNames()).contains("inputParam");

        AssignVariablesData step = (AssignVariablesData) restored.getSteps().get(0);
        Assertions.assertThat(step.getVariableAssignments().get(0).getVarName()).isEqualTo("result");
        Assertions.assertThat(step.getVariableAssignments().get(0).getExpressionOrVarName())
                .isEqualTo("${input}");
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private WorkflowData buildSampleWorkflow(String name) {
        WorkflowData wd = new WorkflowData();
        wd.setName(name);

        AssignVariablesData step = new AssignVariablesData();
        step.setName("step1");
        step.getVariableAssignments().add(new VariableAssignment("result", "${input}"));
        step.setAssignResultToVar("result");
        wd.addSteps(step);
        wd.setResultFrom("result");
        return wd;
    }
}
