package ai.mindconnect.workflow.persistence.pg;

import ai.mindconnect.jdbc.Sql;
import ai.mindconnect.workflow.domain.WorkflowData;
import ai.mindconnect.workflow.persist.WorkflowInstanceSnapshot;
import ai.mindconnect.workflow.persistence.file.FileWorkflowRepositoryFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** A partition moved from the file stores to Postgres: the factory finds what the file factory wrote. */
class PgWorkflowRepositoryFactoryTest {

    private Sql sql;

    @TempDir
    Path dataDir;

    @BeforeEach
    void setUp() {
        sql = TestDb.fresh("mc_workflow", "mc_workflow_instance", "mc_workflow_import");
    }

    private void writeWithFiles(String partition) {
        var files = new FileWorkflowRepositoryFactory(dataDir, partition);
        WorkflowData wf = new WorkflowData();
        wf.setName("approval");
        files.workflowDataRepository().save("approval", wf);
        WorkflowInstanceSnapshot run = new WorkflowInstanceSnapshot();
        run.setWorkflowName("approval");
        run.setSuspendedAt(1_000);
        files.workflowInstanceRepository().save(run);
    }

    @Test
    void importsTheDefinitionsAndTheRunsTheFileStoresKept() {
        writeWithFiles("acme");

        var factory = new PgWorkflowRepositoryFactory(sql, "acme", dataDir);

        assertThat(factory.workflowDataRepository().listIds()).containsExactly("approval");
        assertThat(factory.workflowInstanceRepository().findByWorkflow("approval")).hasSize(1);
        // the rows are in the tables, under the partition
        assertThat(sql.scalar("SELECT count(*) FROM mc_workflow WHERE partition_key = 'acme'", Long.class)).isEqualTo(1);
        assertThat(sql.scalar("SELECT count(*) FROM mc_workflow_instance WHERE partition_key = 'acme'", Long.class)).isEqualTo(1);
    }

    @Test
    void withoutADataDirectoryNothingIsImported() {
        writeWithFiles("acme");

        var factory = new PgWorkflowRepositoryFactory(sql, "acme");

        assertThat(factory.workflowDataRepository().listIds()).isEmpty();
        assertThat(factory.workflowInstanceRepository().findAll()).isEmpty();
    }

    @Test
    void anotherPartitionImportsOnlyItsOwnFiles() {
        writeWithFiles("acme");

        var factory = new PgWorkflowRepositoryFactory(sql, "globex", dataDir);

        assertThat(factory.workflowDataRepository().listIds()).isEmpty();
        assertThat(factory.workflowInstanceRepository().findAll()).isEmpty();
    }
}
