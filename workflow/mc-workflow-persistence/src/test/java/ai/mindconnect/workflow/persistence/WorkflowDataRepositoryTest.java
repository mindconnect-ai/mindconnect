package ai.mindconnect.workflow.persistence;

import ai.mindconnect.workflow.domain.WorkflowData;
import ai.mindconnect.workflow.persistence.file.FileWorkflowDataRepository;
import ai.mindconnect.workflow.persistence.memory.InMemoryWorkflowDataRepository;
import ai.mindconnect.workflow.persistence.port.WorkflowDataRepository;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.file.Path;
import java.util.stream.Stream;

/**
 * One suite over both implementations of the port — the whole point of having a
 * port is that they behave the same, so they are tested the same.
 */
class WorkflowDataRepositoryTest {

    @TempDir
    static Path tempDir;

    static Stream<WorkflowDataRepository> implementations() {
        return Stream.of(
                new InMemoryWorkflowDataRepository(),
                new FileWorkflowDataRepository(tempDir.resolve("defs")));
    }

    @ParameterizedTest
    @MethodSource("implementations")
    void savesLoadsListsAndDeletes(WorkflowDataRepository repo) {
        Assertions.assertThat(repo.findById("greeter")).isEmpty();
        Assertions.assertThat(repo.exists("greeter")).isFalse();

        repo.save("greeter", workflow("greeter"));
        repo.save("pipeline", workflow("pipeline"));

        Assertions.assertThat(repo.exists("greeter")).isTrue();
        Assertions.assertThat(repo.findById("greeter")).get()
                .extracting(WorkflowData::getName).isEqualTo("greeter");
        Assertions.assertThat(repo.listIds()).containsExactly("greeter", "pipeline");

        Assertions.assertThat(repo.delete("greeter")).isTrue();
        Assertions.assertThat(repo.delete("greeter")).isFalse();
        Assertions.assertThat(repo.findById("greeter")).isEmpty();
        Assertions.assertThat(repo.listIds()).containsExactly("pipeline");
    }

    @ParameterizedTest
    @MethodSource("implementations")
    void saveReplacesAnExistingWorkflow(WorkflowDataRepository repo) {
        repo.save("wf", workflow("first"));
        repo.save("wf", workflow("second"));

        Assertions.assertThat(repo.findById("wf")).get()
                .extracting(WorkflowData::getName).isEqualTo("second");
        Assertions.assertThat(repo.listIds()).containsOnlyOnce("wf");
    }

    private static WorkflowData workflow(String name) {
        WorkflowData wf = new WorkflowData();
        wf.setName(name);
        return wf;
    }
}
