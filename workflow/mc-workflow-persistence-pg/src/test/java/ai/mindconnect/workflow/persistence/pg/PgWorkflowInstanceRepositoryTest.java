package ai.mindconnect.workflow.persistence.pg;

import ai.mindconnect.workflow.persist.FrameSnapshot;
import ai.mindconnect.workflow.persist.WorkflowInstanceSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PgWorkflowInstanceRepositoryTest {

    private PgWorkflowInstanceRepository repo;

    @BeforeEach
    void setUp() {
        repo = new PgWorkflowInstanceRepository(TestDb.fresh("mc_workflow_instance")).initSchema();
    }

    private static WorkflowInstanceSnapshot snapshot(String workflow, long suspendedAt) {
        WorkflowInstanceSnapshot s = new WorkflowInstanceSnapshot();
        s.setWorkflowName(workflow);
        s.setSuspendedAt(suspendedAt);
        s.setStartedAt(suspendedAt - 1_000);
        FrameSnapshot root = new FrameSnapshot();
        root.setStepName(workflow);
        Map<String, Object> vars = new HashMap<>();
        vars.put("amount", "42");
        root.setVariables(vars);
        s.setRoot(root);
        return s;
    }

    @Test
    void saveAssignsAnIdAndTheSnapshotComesBackWhole() {
        WorkflowInstanceSnapshot s = snapshot("approval", 1_700_000_000_000L);
        String id = repo.save(s);

        assertThat(id).isNotBlank().isEqualTo(s.getInstanceId());
        WorkflowInstanceSnapshot loaded = repo.findById(id).orElseThrow();
        assertThat(loaded.getWorkflowName()).isEqualTo("approval");
        assertThat(loaded.getSuspendedAt()).isEqualTo(1_700_000_000_000L);
        assertThat(loaded.getRoot().getVariables()).containsEntry("amount", "42");
        assertThat(repo.findById("nobody")).isEmpty();
    }

    @Test
    void savingAgainUnderTheSameIdReplaces() {
        WorkflowInstanceSnapshot s = snapshot("approval", 1_000);
        String id = repo.save(s);
        s.setSuspendedAt(2_000);
        repo.save(s);

        assertThat(repo.findAll()).hasSize(1);
        assertThat(repo.findById(id).orElseThrow().getSuspendedAt()).isEqualTo(2_000);
    }

    @Test
    void listingsAreNewestSuspensionFirstAndByWorkflow() {
        repo.save(snapshot("approval", 1_000));
        repo.save(snapshot("approval", 3_000));
        repo.save(snapshot("ingest", 2_000));

        assertThat(repo.findAll()).extracting(WorkflowInstanceSnapshot::getSuspendedAt).containsExactly(3_000L, 2_000L, 1_000L);
        assertThat(repo.findByWorkflow("approval")).extracting(WorkflowInstanceSnapshot::getSuspendedAt).containsExactly(3_000L, 1_000L);
        assertThat(repo.findByWorkflow("nobody")).isEmpty();
    }

    @Test
    void deleteRemovesOneInstance() {
        String id = repo.save(snapshot("approval", 1_000));
        repo.save(snapshot("approval", 2_000));

        assertThat(repo.delete(id)).isTrue();
        assertThat(repo.delete(id)).isFalse();
        assertThat(repo.findAll()).hasSize(1);
    }
}
