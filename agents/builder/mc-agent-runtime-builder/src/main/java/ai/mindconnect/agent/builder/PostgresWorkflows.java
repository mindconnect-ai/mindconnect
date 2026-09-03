package ai.mindconnect.agent.builder;

import ai.mindconnect.agent.tool.MapToolEnvironment;
import ai.mindconnect.jdbc.Sql;
import ai.mindconnect.workflow.jackson.JacksonWorkflowSerializer;
import ai.mindconnect.workflow.jackson.WorkflowObjectMapperFactory;
import ai.mindconnect.workflow.persistence.pg.PgWorkflowDataRepository;
import ai.mindconnect.workflow.persistence.port.WorkflowDataRepository;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Workflows in Postgres for {@link AgentRuntimeBuilder#usePostgres}: opens the
 * store, hands it to the tools, seeds it from the classpath. Kept apart from
 * the builder so the workflow modules stay optional — nothing here is linked
 * unless {@link #present()} said the classes exist.
 */
final class PostgresWorkflows {

    private PostgresWorkflows() {
    }

    static boolean present() {
        try {
            Class.forName("ai.mindconnect.workflow.persistence.pg.PgWorkflowDataRepository");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    static WorkflowDataRepository open(Sql sql) {
        return new PgWorkflowDataRepository(sql).initSchema();
    }

    /** The workflow tools and the attach support find the store here instead of opening files. */
    static void register(MapToolEnvironment.Builder env, WorkflowDataRepository workflows) {
        env.service(WorkflowDataRepository.class, workflows);
    }

    /** Same resources {@code workflowFromClasspath} copies into the directory, saved under their base name. */
    static void seed(WorkflowDataRepository workflows, List<String> resources) {
        var serializer = new JacksonWorkflowSerializer(WorkflowObjectMapperFactory.create());
        for (String resource : resources) {
            String name = Path.of(resource).getFileName().toString();
            String id = name.endsWith(".json") ? name.substring(0, name.length() - ".json".length()) : name;
            try (InputStream in = AgentRuntimeBuilder.classpath(resource)) {
                workflows.save(id, serializer.read(in));
            } catch (IOException e) {
                throw new UncheckedIOException("Could not seed workflow " + resource, e);
            }
        }
    }
}
