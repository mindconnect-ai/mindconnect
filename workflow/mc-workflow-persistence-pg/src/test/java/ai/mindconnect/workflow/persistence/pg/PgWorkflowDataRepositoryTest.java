package ai.mindconnect.workflow.persistence.pg;

import ai.mindconnect.jdbc.Sql;
import ai.mindconnect.workflow.domain.WorkflowData;
import ai.mindconnect.workflow.persistence.file.FileWorkflowDataRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** The contract {@code WorkflowDataRepositoryTest} checks for the file and memory stores, against Postgres. */
class PgWorkflowDataRepositoryTest {

    private Sql sql;
    private PgWorkflowDataRepository repo;

    @TempDir
    Path dataDir;

    @BeforeEach
    void setUp() {
        sql = TestDb.fresh("mc_workflow", "mc_workflow_import");
        repo = new PgWorkflowDataRepository(sql, "test").initSchema();
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

    @Test
    void onePartitionNeverSeesTheRowsOfAnother() {
        var other = new PgWorkflowDataRepository(sql, "other").initSchema();
        repo.save("wf", workflow("mine"));
        other.save("wf", workflow("theirs"));
        other.save("only-there", workflow("only-there"));

        assertThat(repo.listIds()).containsExactly("wf");
        assertThat(repo.findById("wf")).get().extracting(WorkflowData::getName).isEqualTo("mine");
        assertThat(repo.exists("only-there")).isFalse();
        assertThat(repo.delete("only-there")).isFalse();

        assertThat(other.delete("wf")).isTrue();
        assertThat(repo.findById("wf")).isPresent();
    }

    // ── the one-time import ─────────────────────────────────────────────────

    /** Two definitions as the file store writes them, and a file that is not one. */
    private Path filesOf(String partition) throws Exception {
        var files = new FileWorkflowDataRepository(dataDir, partition);
        files.save("greeter", workflow("greeter of " + partition));
        files.save("pipeline", workflow("pipeline"));
        Path dir = FileWorkflowDataRepository.directory(dataDir, partition);
        Files.writeString(dir.resolve("broken.json"), "{ not a workflow");
        return dir;
    }

    @Test
    void importsTheFilesIntoAnEmptyPartitionAndKeepsThem() throws Exception {
        Path dir = filesOf("test");

        assertThat(repo.importFiles(dir)).isEqualTo(2);

        assertThat(repo.listIds()).containsExactly("greeter", "pipeline");
        assertThat(repo.findById("greeter")).get().extracting(WorkflowData::getName).isEqualTo("greeter of test");
        assertThat(new FileWorkflowDataRepository(dataDir, "test").listIds()).containsExactly("broken", "greeter", "pipeline");
    }

    @Test
    void importsOnceNotAgainAfterEveryRowWasDeleted() throws Exception {
        Path dir = filesOf("test");
        repo.importFiles(dir);
        repo.delete("greeter");
        repo.delete("pipeline");

        assertThat(repo.importFiles(dir)).isZero();
        assertThat(new PgWorkflowDataRepository(sql, "test").initSchema().importFiles(dir)).isZero();
        assertThat(repo.listIds()).isEmpty();
    }

    @Test
    void aPartitionThatAlreadyHasRowsImportsNothing() throws Exception {
        Path dir = filesOf("test");
        repo.save("own", workflow("own"));

        assertThat(repo.importFiles(dir)).isZero();
        assertThat(repo.listIds()).containsExactly("own");
    }

    @Test
    void theImportIsPerPartition() throws Exception {
        var other = new PgWorkflowDataRepository(sql, "other").initSchema();
        other.save("own", workflow("own"));
        Path mine = filesOf("test");
        Path theirs = filesOf("other");

        assertThat(repo.importFiles(mine)).isEqualTo(2);
        assertThat(other.importFiles(theirs)).isZero();

        assertThat(repo.listIds()).containsExactly("greeter", "pipeline");
        assertThat(other.listIds()).containsExactly("own");
    }

    @Test
    void noDirectoryIsNothingToImport() {
        assertThat(repo.importFiles(dataDir.resolve("missing"))).isZero();
        assertThat(repo.importFiles(null)).isZero();
        assertThat(sql.scalar("SELECT count(*) FROM mc_workflow_import", Long.class)).isZero();
    }
}
