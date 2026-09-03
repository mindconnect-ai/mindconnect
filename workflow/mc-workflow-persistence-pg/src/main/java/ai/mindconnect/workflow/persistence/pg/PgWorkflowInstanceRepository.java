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
 * when listed, as the file store lists them.
 *
 * <p>Written through the same {@link SnapshotSerializer} as the file store,
 * so the check that every variable survives a JSON round trip — the whole
 * point of a snapshot — runs here too, and refuses the save the same way.
 */
public final class PgWorkflowInstanceRepository implements WorkflowInstanceRepository {

    private static final String DDL = """
            CREATE TABLE IF NOT EXISTS mc_workflow_instance (
                id            TEXT PRIMARY KEY,
                workflow_name TEXT,
                status        TEXT,
                suspended_at  BIGINT,
                updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
                doc           JSONB NOT NULL
            );
            CREATE INDEX IF NOT EXISTS mc_workflow_instance_workflow_name_idx
                ON mc_workflow_instance (workflow_name);
            """;

    private final Sql sql;
    private final SnapshotSerializer serializer;

    public PgWorkflowInstanceRepository(DataSource dataSource) {
        this(Sql.of(dataSource));
    }

    public PgWorkflowInstanceRepository(Sql sql) {
        this(sql, new SnapshotSerializer());
    }

    public PgWorkflowInstanceRepository(Sql sql, SnapshotSerializer serializer) {
        this.sql = sql;
        this.serializer = serializer;
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
        sql.update("INSERT INTO mc_workflow_instance (id, workflow_name, status, suspended_at, updated_at, doc) "
                        + "VALUES (?, ?, ?, ?, now(), ?) ON CONFLICT (id) DO UPDATE SET "
                        + "workflow_name = EXCLUDED.workflow_name, status = EXCLUDED.status, "
                        + "suspended_at = EXCLUDED.suspended_at, updated_at = now(), doc = EXCLUDED.doc",
                snapshot.getInstanceId(), snapshot.getWorkflowName(),
                snapshot.getStatus() == null ? null : snapshot.getStatus().name(),
                snapshot.getSuspendedAt(), Jsonb.of(json));
        return snapshot.getInstanceId();
    }

    @Override
    public Optional<WorkflowInstanceSnapshot> findById(String instanceId) {
        return sql.queryOne("SELECT doc FROM mc_workflow_instance WHERE id = ?",
                row -> serializer.fromJson(row.string("doc")), instanceId);
    }

    @Override
    public List<WorkflowInstanceSnapshot> findByWorkflow(String workflowName) {
        return sql.query("SELECT doc FROM mc_workflow_instance WHERE workflow_name = ? ORDER BY suspended_at DESC, id",
                row -> serializer.fromJson(row.string("doc")), workflowName);
    }

    @Override
    public List<WorkflowInstanceSnapshot> findAll() {
        return sql.query("SELECT doc FROM mc_workflow_instance ORDER BY suspended_at DESC, id",
                row -> serializer.fromJson(row.string("doc")));
    }

    @Override
    public boolean delete(String instanceId) {
        return sql.update("DELETE FROM mc_workflow_instance WHERE id = ?", instanceId) > 0;
    }
}
