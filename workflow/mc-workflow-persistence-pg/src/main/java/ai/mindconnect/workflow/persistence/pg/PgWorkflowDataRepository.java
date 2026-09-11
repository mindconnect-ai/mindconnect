package ai.mindconnect.workflow.persistence.pg;

import ai.mindconnect.jdbc.Jsonb;
import ai.mindconnect.jdbc.Sql;
import ai.mindconnect.workflow.domain.WorkflowData;
import ai.mindconnect.workflow.jackson.JacksonWorkflowSerializer;
import ai.mindconnect.workflow.jackson.WorkflowObjectMapperFactory;
import ai.mindconnect.workflow.persistence.port.WorkflowDataRepository;

import javax.sql.DataSource;
import java.util.List;
import java.util.Optional;

/**
 * {@link WorkflowDataRepository} on Postgres: one row of {@code mc_workflow}
 * per definition, keyed by the repository's partition and the id the caller
 * saves it under — which the file store turns into a file name and this store
 * keeps as it is. One partition never sees the rows of another.
 *
 * <p>The document is written by the workflow area's own
 * {@link JacksonWorkflowSerializer}, not by a generic mapper: step
 * polymorphism and the mixins live there, and a definition in the database
 * must read back exactly as the same definition in a file would.
 */
public final class PgWorkflowDataRepository implements WorkflowDataRepository {

    private static final String DDL = """
            CREATE TABLE IF NOT EXISTS mc_workflow (
                partition_key TEXT NOT NULL,
                id            TEXT NOT NULL,
                name          TEXT,
                updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
                doc           JSONB NOT NULL,
                PRIMARY KEY (partition_key, id)
            );
            """;

    private final Sql sql;
    private final JacksonWorkflowSerializer serializer;
    private final String partition;

    public PgWorkflowDataRepository(DataSource dataSource, String partition) {
        this(Sql.of(dataSource), partition);
    }

    public PgWorkflowDataRepository(Sql sql, String partition) {
        this(sql, partition, new JacksonWorkflowSerializer(WorkflowObjectMapperFactory.create()));
    }

    public PgWorkflowDataRepository(Sql sql, String partition, JacksonWorkflowSerializer serializer) {
        if (partition == null || partition.isBlank()) throw new IllegalArgumentException("A partition is required");
        this.sql = sql;
        this.serializer = serializer;
        this.partition = partition;
    }

    /** Runs the idempotent DDL ({@code CREATE TABLE IF NOT EXISTS …}). */
    public PgWorkflowDataRepository initSchema() {
        sql.execute(DDL);
        return this;
    }

    @Override
    public List<String> listIds() {
        return sql.query("SELECT id FROM mc_workflow WHERE partition_key = ? ORDER BY id", row -> row.string("id"), partition);
    }

    @Override
    public Optional<WorkflowData> findById(String id) {
        return sql.queryOne("SELECT doc FROM mc_workflow WHERE partition_key = ? AND id = ?",
                row -> serializer.read(row.string("doc")), partition, id);
    }

    @Override
    public void save(String id, WorkflowData workflow) {
        sql.update("INSERT INTO mc_workflow (partition_key, id, name, updated_at, doc) VALUES (?, ?, ?, now(), ?) "
                        + "ON CONFLICT (partition_key, id) DO UPDATE SET name = EXCLUDED.name, updated_at = now(), doc = EXCLUDED.doc",
                partition, id, workflow.getName(), Jsonb.of(serializer.write(workflow)));
    }

    @Override
    public boolean delete(String id) {
        return sql.update("DELETE FROM mc_workflow WHERE partition_key = ? AND id = ?", partition, id) > 0;
    }

    @Override
    public boolean exists(String id) {
        return sql.scalar("SELECT count(*) FROM mc_workflow WHERE partition_key = ? AND id = ?", Long.class, partition, id) > 0;
    }
}
