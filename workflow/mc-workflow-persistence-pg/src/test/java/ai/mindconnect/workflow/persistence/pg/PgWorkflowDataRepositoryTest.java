package ai.mindconnect.workflow.persistence.pg;

import ai.mindconnect.workflow.domain.WorkflowData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The contract {@code WorkflowDataRepositoryTest} checks for the file and memory stores, against Postgres. */
class PgWorkflowDataRepositoryTest {

    private PgWorkflowDataRepository repo;

    @BeforeEach
    void setUp() {
        repo = new PgWorkflowDataRepository(TestDb.fresh("mc_workflow")).initSchema();
    }

    private static WorkflowData workflow(String name) {
        WorkflowData wf = new WorkflowData();
        wf.setName(name);
        return wf;
    }

    @Test
    void savesLoadsListsAndDeletes() {
        assertThat(repo.findById("greeter")).isEmpty();
        assertThat(repo.exists("greeter")).isFalse();

        repo.save("greeter", workflow("greeter"));
        repo.save("pipeline", workflow("pipeline"));

        assertThat(repo.exists("greeter")).isTrue();
        assertThat(repo.findById("greeter")).get().extracting(WorkflowData::getName).isEqualTo("greeter");
        assertThat(repo.listIds()).containsExactly("greeter", "pipeline");

        assertThat(repo.delete("greeter")).isTrue();
        assertThat(repo.delete("greeter")).isFalse();
        assertThat(repo.findById("greeter")).isEmpty();
        assertThat(repo.listIds()).containsExactly("pipeline");
    }

    @Test
    void saveReplacesAnExistingWorkflow() {
        repo.save("wf", workflow("first"));
        repo.save("wf", workflow("second"));
        assertThat(repo.findById("wf")).get().extracting(WorkflowData::getName).isEqualTo("second");
        assertThat(repo.listIds()).containsOnlyOnce("wf");
    }

    @Test
    void anIdThatIsNotAFileNameIsKeptAsItIs() {
        repo.save("team a/greeter v2", workflow("greeter"));
        assertThat(repo.listIds()).containsExactly("team a/greeter v2");
        assertThat(repo.findById("team a/greeter v2")).isPresent();
    }
}
