package ai.mindconnect.workflow.persistence.pg;

import ai.mindconnect.jdbc.Sql;
import ai.mindconnect.workflow.persist.FrameSnapshot;
import ai.mindconnect.workflow.persist.WorkflowInstanceSnapshot;
import ai.mindconnect.workflow.persistence.file.FileWorkflowInstanceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PgWorkflowInstanceRepositoryTest {

    private Sql sql;
    private PgWorkflowInstanceRepository repo;

    @TempDir
    Path dataDir;

    @BeforeEach
    void setUp() {
        sql = TestDb.fresh("mc_workflow_instance", "mc_workflow_import");
        repo = new PgWorkflowInstanceRepository(sql, "test").initSchema();
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

    @Test
    void onePartitionNeverSeesTheRunsOfAnother() {
        var other = new PgWorkflowInstanceRepository(sql, "other").initSchema();
        String mine = repo.save(snapshot("approval", 1_000));
        String theirs = other.save(snapshot("approval", 2_000));

        assertThat(repo.findAll()).extracting(WorkflowInstanceSnapshot::getInstanceId).containsExactly(mine);
        assertThat(repo.findByWorkflow("approval")).hasSize(1);
        assertThat(repo.findById(theirs)).isEmpty();
        assertThat(repo.delete(theirs)).isFalse();
        assertThat(other.findById(theirs)).isPresent();
    }

    // ── the one-time import ─────────────────────────────────────────────────

    @Test
    void importsTheFilesOnceIntoAnEmptyPartitionAndKeepsThem() throws Exception {
        var files = new FileWorkflowInstanceRepository(dataDir, "test");
        String older = files.save(snapshot("approval", 1_000));
        String newer = files.save(snapshot("ingest", 2_000));
        Path dir = FileWorkflowInstanceRepository.directory(dataDir, "test");
        Files.writeString(dir.resolve("broken.json"), "[]");

        assertThat(repo.importFiles(dir)).isEqualTo(2);

        assertThat(repo.findAll()).extracting(WorkflowInstanceSnapshot::getInstanceId).containsExactly(newer, older);
        assertThat(repo.findById(older).orElseThrow().getRoot().getVariables()).containsEntry("amount", "42");
        assertThat(files.findById(older)).isPresent();

        // a resumed run is deleted — and does not come back from its file
        repo.delete(older);
        repo.delete(newer);
        assertThat(repo.importFiles(dir)).isZero();
        assertThat(repo.findAll()).isEmpty();
    }

    @Test
    void aPartitionThatAlreadyHasRunsImportsNothingAndOthersImportTheirOwn() {
        var other = new PgWorkflowInstanceRepository(sql, "other").initSchema();
        new FileWorkflowInstanceRepository(dataDir, "test").save(snapshot("approval", 1_000));
        new FileWorkflowInstanceRepository(dataDir, "other").save(snapshot("ingest", 2_000));
        String own = repo.save(snapshot("own", 3_000));

        assertThat(repo.importFiles(FileWorkflowInstanceRepository.directory(dataDir, "test"))).isZero();
        assertThat(other.importFiles(FileWorkflowInstanceRepository.directory(dataDir, "other"))).isEqualTo(1);

        assertThat(repo.findAll()).extracting(WorkflowInstanceSnapshot::getInstanceId).containsExactly(own);
        assertThat(other.findAll()).extracting(WorkflowInstanceSnapshot::getWorkflowName).containsExactly("ingest");
    }
}
