package ai.mindconnect.workflow.persistence.pg;

import ai.mindconnect.jdbc.Jsonb;
import ai.mindconnect.jdbc.Sql;
import ai.mindconnect.workflow.persist.WorkflowInstanceSnapshot;
import ai.mindconnect.workflow.persistence.file.SnapshotSerializer;
import ai.mindconnect.workflow.persistence.port.WorkflowInstanceRepository;

import javax.sql.DataSource;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link WorkflowInstanceRepository} on Postgres: one row of
 * {@code mc_workflow_instance} per suspended run, newest suspension first
 * when listed, as the file store lists them. Rows are keyed by the
 * repository's partition; one partition never sees the runs of another.
 *
 * <p>Written through the same {@link SnapshotSerializer} as the file store,
 * so the check that every variable survives a JSON round trip — the whole
 * point of a snapshot — runs here too, and refuses the save the same way.
 */
public final class PgWorkflowInstanceRepository implements WorkflowInstanceRepository {

    private static final String DDL = """
            CREATE TABLE IF NOT EXISTS mc_workflow_instance (
                partition_key TEXT NOT NULL,
                id            TEXT NOT NULL,
                workflow_name TEXT,
                status        TEXT,
                suspended_at  BIGINT,
                updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
                doc           JSONB NOT NULL,
                PRIMARY KEY (partition_key, id)
            );
            CREATE INDEX IF NOT EXISTS mc_workflow_instance_workflow_name_idx
                ON mc_workflow_instance (partition_key, workflow_name);
            """;

    private final Sql sql;
    private final SnapshotSerializer serializer;
    private final String partition;

    public PgWorkflowInstanceRepository(DataSource dataSource, String partition) {
        this(Sql.of(dataSource), partition);
    }

    public PgWorkflowInstanceRepository(Sql sql, String partition) {
        this(sql, partition, new SnapshotSerializer());
    }

    public PgWorkflowInstanceRepository(Sql sql, String partition, SnapshotSerializer serializer) {
        if (partition == null || partition.isBlank()) throw new IllegalArgumentException("A partition is required");
        this.sql = sql;
        this.serializer = serializer;
        this.partition = partition;
    }

    /** Runs the idempotent DDL ({@code CREATE TABLE IF NOT EXISTS …}). */
    public PgWorkflowInstanceRepository initSchema() {
        sql.execute(DDL);
        return this;
    }

    @Override
    public String save(WorkflowInstanceSnapshot snapshot) {
        if (snapshot.getInstanceId() == null || snapshot.getInstanceId().isBlank()) {
            snapshot.setInstanceId(UUID.randomUUID().toString());
        }
        String json = serializer.toJson(snapshot);   // validates before anything touches the database
        sql.update("INSERT INTO mc_workflow_instance (partition_key, id, workflow_name, status, suspended_at, updated_at, doc) "
                        + "VALUES (?, ?, ?, ?, ?, now(), ?) ON CONFLICT (partition_key, id) DO UPDATE SET "
                        + "workflow_name = EXCLUDED.workflow_name, status = EXCLUDED.status, "
                        + "suspended_at = EXCLUDED.suspended_at, updated_at = now(), doc = EXCLUDED.doc",
                partition, snapshot.getInstanceId(), snapshot.getWorkflowName(),
                snapshot.getStatus() == null ? null : snapshot.getStatus().name(),
                snapshot.getSuspendedAt(), Jsonb.of(json));
        return snapshot.getInstanceId();
    }

    @Override
    public Optional<WorkflowInstanceSnapshot> findById(String instanceId) {
        return sql.queryOne("SELECT doc FROM mc_workflow_instance WHERE partition_key = ? AND id = ?",
                row -> serializer.fromJson(row.string("doc")), partition, instanceId);
    }

    @Override
    public List<WorkflowInstanceSnapshot> findByWorkflow(String workflowName) {
        return sql.query("SELECT doc FROM mc_workflow_instance WHERE partition_key = ? AND workflow_name = ? ORDER BY suspended_at DESC, id",
                row -> serializer.fromJson(row.string("doc")), partition, workflowName);
    }

    @Override
    public List<WorkflowInstanceSnapshot> findAll() {
        return sql.query("SELECT doc FROM mc_workflow_instance WHERE partition_key = ? ORDER BY suspended_at DESC, id",
                row -> serializer.fromJson(row.string("doc")), partition);
    }

    @Override
    public boolean delete(String instanceId) {
        return sql.update("DELETE FROM mc_workflow_instance WHERE partition_key = ? AND id = ?", partition, instanceId) > 0;
    }
}
