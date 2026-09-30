package ai.mindconnect.agent.builder;

import ai.mindconnect.agent.UserId;
import ai.mindconnect.agent.runtime.domain.AgentDefinition;
import ai.mindconnect.agent.runtime.domain.AgentSession;
import ai.mindconnect.agent.runtime.feature.Persistence;
import ai.mindconnect.agent.runtime.feature.RuntimeFeature;
import ai.mindconnect.agent.runtime.feature.core.CoreFeature;
import ai.mindconnect.agent.runtime.feature.fileupload.FileUploadFeature;
import ai.mindconnect.agent.runtime.feature.taskqueue.TaskQueueFeature;
import ai.mindconnect.agent.runtime.feature.tools.ToolsFeature;
import ai.mindconnect.agent.runtime.feature.workflows.WorkflowsFeature;
import ai.mindconnect.filestore.FileStore;
import ai.mindconnect.llm.domain.LlmConfig;
import ai.mindconnect.taskqueue.TaskQueue;
import ai.mindconnect.taskqueue.jdbc.JdbcTaskStore;
import ai.mindconnect.taskqueue.TaskStore;
import ai.mindconnect.jdbc.Sql;
import ai.mindconnect.workflow.domain.WorkflowData;
import ai.mindconnect.workflow.persist.WorkflowInstanceSnapshot;
import ai.mindconnect.workflow.persistence.file.FileWorkflowDataRepository;
import ai.mindconnect.workflow.persistence.file.FileWorkflowInstanceRepository;
import ai.mindconnect.workflow.persistence.port.WorkflowDataRepository;
import ai.mindconnect.workflow.persistence.port.WorkflowInstanceRepository;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;

import javax.sql.DataSource;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The features on Postgres persistence: every store a table, the workflow
 * store and the file store included, the task queue on a JDBC store. Needs a
 * Postgres — the pgvector container the vector-store tests use
 * ({@code podman run -d -p 5433:5432 -e POSTGRES_PASSWORD=test pgvector/pgvector:pg17-trixie})
 * — and skips itself without one. Each run works in a namespace of its own.
 */
class PostgresCombinationsTest {

    private static final String URL = System.getenv().getOrDefault("MC_PGVECTOR_TEST_URL", "jdbc:postgresql://localhost:5433/postgres");
    private static final String USER = System.getenv().getOrDefault("MC_PGVECTOR_TEST_USER", "postgres");
    private static final String PASSWORD = System.getenv().getOrDefault("MC_PGVECTOR_TEST_PASSWORD", "test");

    private static DataSource dataSource() {
        var ds = new PGSimpleDataSource();
        ds.setUrl(URL);
        ds.setUser(USER);
        ds.setPassword(PASSWORD);
        return ds;
    }

    private static boolean reachable() {
        try (Connection c = dataSource().getConnection()) {
            return c.isValid(2);
        } catch (Exception e) {
            return false;
        }
    }

    @Test
    void everyStoreOnPostgresAndTheQueueOnJdbc() throws Exception {
        assumeTrue(reachable(), "no Postgres reachable on " + URL + " — skipping");
        String namespace = "t-" + UUID.randomUUID().toString().substring(0, 8);
        AgentRuntimeBuilder builder = AgentRuntimeBuilder.of(Persistence.postgres(dataSource(), Files.createTempDirectory("mc-pg")))
                .namespace(namespace)
                .install(new ToolsFeature())
                .install(new WorkflowsFeature().seed("workflows/hello.json"))
                .install(new FileUploadFeature())
                .install(new TaskQueueFeature().jdbc().nodeId("test-" + namespace));
        builder.feature(CoreFeature.class)
                .llmConfig(LlmConfig.lmStudio("test-llm", "m", "http://localhost:9"))
                .agentDefinition(AgentDefinition.create("pg-agent", "pg", "You are a test.", null, "test-llm"));
        try (AgentRuntime runtime = builder.build()) {
            assertThat(runtime.features().all()).extracting(RuntimeFeature::name)
                    .containsExactly("core", "tools", "workflows", "file-upload", "task-queue");
            assertThat(runtime.beans().get(TaskStore.class)).isInstanceOf(JdbcTaskStore.class);
            assertThat(runtime.beans().get(TaskQueue.class)).isNotNull();

            // seeds landed in the tables …
            assertThat(runtime.llmConfigs().findByName("test-llm")).isPresent();
            assertThat(runtime.beans().get(WorkflowDataRepository.class).findById("hello")).isPresent();
            // … a session with its conversation …
            AgentSession session = runtime.openSession("pg-agent", UserId.of("u"));
            runtime.conversationManager().addMessageToConversation(session.conversationId(), "u",
                    ai.mindconnect.message.domain.ParticipantType.USER,
                    ai.mindconnect.message.domain.MessageType.CHAT, "hello", null);
            assertThat(runtime.conversationManager().loadCompleteHistory(session.conversationId()).messages()).hasSize(1);
            // … and a file in the Postgres file store
            FileStore files = runtime.beans().get(FileStore.class);
            var stored = files.save("note.txt", "text/plain", new ByteArrayInputStream("hi".getBytes(StandardCharsets.UTF_8)));
            assertThat(new String(files.content(stored.id()).readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("hi");

            // deleting the session takes the conversation with it — on Postgres too
            runtime.sessionService().deleteSession(session.id());
            assertThat(runtime.conversationManager().findById(session.conversationId())).isEmpty();
            assertThatThrownBy(() -> runtime.sessionService().findSession(session.id())).isInstanceOf(Exception.class);
        }
    }

    @Test
    void workflowsAreTablesAndTheFilesOfFilePersistenceAreImportedOnce() throws Exception {
        assumeTrue(reachable(), "no Postgres reachable on " + URL + " — skipping");
        String namespace = "t-" + UUID.randomUUID().toString().substring(0, 8);
        Path dataDir = Files.createTempDirectory("mc-pg");
        // what an installation on file persistence left behind
        WorkflowData fromFile = new WorkflowData();
        fromFile.setName("from-file");
        new FileWorkflowDataRepository(dataDir, namespace).save("from-file", fromFile);
        WorkflowInstanceSnapshot halted = new WorkflowInstanceSnapshot();
        halted.setWorkflowName("from-file");
        halted.setSuspendedAt(1_000);
        String haltedId = new FileWorkflowInstanceRepository(dataDir, namespace).save(halted);
        Sql sql = Sql.of(dataSource());

        try (AgentRuntime runtime = AgentRuntimeBuilder.of(Persistence.postgres(dataSource(), dataDir))
                .namespace(namespace)
                .install(new WorkflowsFeature())
                .build()) {
            WorkflowDataRepository workflows = runtime.beans().get(WorkflowDataRepository.class);
            WorkflowInstanceRepository instances = runtime.beans().get(WorkflowInstanceRepository.class);

            assertThat(workflows.listIds()).containsExactly("from-file");
            assertThat(instances.findById(haltedId)).isPresent();

            // a new definition is a row, not a file
            WorkflowData created = new WorkflowData();
            created.setName("created");
            workflows.save("created", created);
            assertThat(sql.query("SELECT id FROM mc_workflow WHERE partition_key = ? ORDER BY id",
                    row -> row.string("id"), namespace)).containsExactly("created", "from-file");
            assertThat(sql.scalar("SELECT count(*) FROM mc_workflow_instance WHERE partition_key = ?", Long.class, namespace))
                    .isEqualTo(1);
            assertThat(new FileWorkflowDataRepository(dataDir, namespace).listIds()).containsExactly("from-file");

            workflows.delete("created");
            workflows.delete("from-file");
            instances.delete(haltedId);
        }

        // the next start does not bring the deleted ones back from the files
        try (AgentRuntime runtime = AgentRuntimeBuilder.of(Persistence.postgres(dataSource(), dataDir))
                .namespace(namespace)
                .install(new WorkflowsFeature())
                .build()) {
            assertThat(runtime.beans().get(WorkflowDataRepository.class).listIds()).isEmpty();
            assertThat(runtime.beans().get(WorkflowInstanceRepository.class).findAll()).isEmpty();
        }
    }

    @Test
    void jdbcQueueNeedsPostgresPersistence() throws Exception {
        AgentRuntimeBuilder builder = AgentRuntimeBuilder.of(Persistence.inMemory())
                .install(new TaskQueueFeature().jdbc());
        assertThatThrownBy(() -> { try (AgentRuntime r = builder.build()) { r.beans().get(TaskQueue.class); } })
                .hasMessageContaining("needs Postgres persistence");
    }
}
