package ai.mindconnect.agent.runtime.feature.workflows;

import ai.mindconnect.agent.Namespace;
import ai.mindconnect.agent.runtime.feature.ConfigurableFeature;
import ai.mindconnect.agent.runtime.feature.FeatureContext;
import ai.mindconnect.agent.runtime.feature.NamespaceRouting;
import ai.mindconnect.agent.runtime.feature.Persistence;
import ai.mindconnect.workflow.jackson.JacksonWorkflowSerializer;
import ai.mindconnect.workflow.jackson.WorkflowObjectMapperFactory;
import ai.mindconnect.workflow.persistence.file.FileWorkflowRepositoryFactory;
import ai.mindconnect.workflow.persistence.memory.InMemoryWorkflowRepositoryFactory;
import ai.mindconnect.workflow.persistence.pg.PgWorkflowRepositoryFactory;
import ai.mindconnect.workflow.persistence.port.WorkflowDataRepository;
import ai.mindconnect.workflow.persistence.port.WorkflowInstanceRepository;
import ai.mindconnect.workflow.persistence.port.WorkflowRepositoryFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Persisted workflows as tools: the workflow store the runtime and its tools
 * share — files, memory or Postgres, following the runtime's persistence —
 * seeded from the classpath on start. The workflow tools
 * ({@code mc-agent-tools-workflow}) find the store in their environment.
 */
public class WorkflowsFeature extends ConfigurableFeature {

    private final List<String> resources = new ArrayList<>();

    /** A workflow JSON on the classpath, saved into the store on start under its file name. */
    public WorkflowsFeature seed(String classpathResource) {
        changing();
        resources.add(classpathResource);
        return this;
    }

    @Override
    public String name() {
        return "workflows";
    }

    @Override
    protected void install(FeatureContext ctx) {
        // The factory per namespace: the persistence setting looked at once, the routing builds the rest.
        Function<Namespace, WorkflowRepositoryFactory> factories = switch (ctx.persistence()) {
            case Persistence.InMemory m -> ns -> new InMemoryWorkflowRepositoryFactory(m.dataDir(), ns.value());
            case Persistence.File f -> ns -> new FileWorkflowRepositoryFactory(f.dataDir(), ns.value());
            case Persistence.Postgres p -> ns -> new PgWorkflowRepositoryFactory(ctx.require(ai.mindconnect.jdbc.Sql.class), ns.value());
        };
        ctx.bean(WorkflowDataRepository.class, () -> ctx.require(NamespaceRouting.class).route(
                WorkflowDataRepository.class, ns -> factories.apply(ns).workflowDataRepository()));
        ctx.bean(WorkflowInstanceRepository.class, () -> ctx.require(NamespaceRouting.class).route(
                WorkflowInstanceRepository.class, ns -> factories.apply(ns).workflowInstanceRepository()));
        ctx.onStart(() -> {
            if (resources.isEmpty()) return;
            var store = ctx.require(WorkflowDataRepository.class);
            var serializer = new JacksonWorkflowSerializer(WorkflowObjectMapperFactory.create());
            for (String resource : resources) {
                String name = Path.of(resource).getFileName().toString();
                String id = name.endsWith(".json") ? name.substring(0, name.length() - ".json".length()) : name;
                try (InputStream in = classpath(resource)) {
                    store.save(id, serializer.read(in));
                } catch (IOException e) {
                    throw new UncheckedIOException("Could not seed workflow " + resource, e);
                }
            }
        });
    }

    private static InputStream classpath(String resource) {
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        if (cl == null) cl = WorkflowsFeature.class.getClassLoader();
        InputStream in = cl.getResourceAsStream(resource);
        if (in == null) {
            throw new IllegalArgumentException("Classpath resource not found: " + resource);
        }
        return in;
    }
}
