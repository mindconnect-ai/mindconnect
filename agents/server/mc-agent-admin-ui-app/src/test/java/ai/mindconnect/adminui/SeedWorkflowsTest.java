package ai.mindconnect.adminui;

import ai.mindconnect.agent.tools.workflow.step.AgentCallData;
import ai.mindconnect.agent.tools.workflow.step.ToolCallData;
import ai.mindconnect.workflow.domain.StepData;
import ai.mindconnect.workflow.domain.StepContainerData;
import ai.mindconnect.workflow.domain.WorkflowData;
import ai.mindconnect.workflow.jackson.JacksonWorkflowSerializer;
import ai.mindconnect.workflow.jackson.WorkflowObjectMapperFactory;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every bundled workflow seed must deserialize through the same serializer the
 * runtime uses — a hand-edited seed that silently fails to load would
 * otherwise only surface at startup. The word-to-markdown seed additionally
 * proves the agent/tool step classes referenced by {@code @class} resolve.
 */
class SeedWorkflowsTest {

    private static final JacksonWorkflowSerializer SERIALIZER =
            new JacksonWorkflowSerializer(WorkflowObjectMapperFactory.create());

    @Test
    void everyBundledSeedDeserializes() throws IOException {
        Path dir = Path.of("src/main/resources/initial-data/workflows");
        try (Stream<Path> files = Files.list(dir)) {
            files.filter(p -> p.toString().endsWith(".json")).forEach(p -> {
                WorkflowData wf = SERIALIZER.read(p);
                assertThat(wf.getName()).as(p.toString()).isNotBlank();
            });
        }
    }

    @Test
    void wordToMarkdownUsesTheAgentAndToolSteps() {
        try (InputStream is = getClass().getResourceAsStream("/initial-data/workflows/word-to-markdown.json")) {
            WorkflowData wf = SERIALIZER.read(is);

            assertThat(wf.getName()).isEqualTo("word-to-markdown");
            assertThat(wf.getParams().getProperties()).containsKeys("wordFile", "targetDir");
            assertThat(wf.getParams().isRequired("wordFile")).isTrue();

            List<StepData> all = flatten(wf.getSteps());
            assertThat(all).anySatisfy(s -> {
                assertThat(s).isInstanceOf(ToolCallData.class);
                assertThat(((ToolCallData) s).getTool()).isEqualTo("document_sections");
            });
            assertThat(all).anySatisfy(s -> {
                assertThat(s).isInstanceOf(AgentCallData.class);
                assertThat(((AgentCallData) s).getAgent()).isEqualTo("Summarizer");
            });
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static List<StepData> flatten(List<? extends StepData> steps) {
        List<StepData> out = new ArrayList<>();
        for (StepData step : steps) {
            out.add(step);
            if (step instanceof StepContainerData container) {
                out.addAll(flatten(container.getSteps()));
            }
        }
        return out;
    }
}
