package ai.mindconnect.adminui;

import ai.mindconnect.initialdata.ImportInitialDataInstaller;
import ai.mindconnect.workflow.jackson.JacksonWorkflowSerializer;
import ai.mindconnect.workflow.jackson.WorkflowObjectMapperFactory;
import ai.mindconnect.workflow.persistence.port.WorkflowDataRepository;
import org.springframework.boot.ApplicationArguments;
import ai.mindconnect.agent.Namespace;
import ai.mindconnect.agent.ScopeSupplier;
import ai.mindconnect.agent.StartupScope;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.io.InputStream;

/**
 * Seeds the bundled example workflows on startup — same skip-if-present
 * semantics as {@link InitialDataLoader} uses for LLM configs and agent
 * definitions.
 *
 * <p>Goes through the {@link WorkflowDataRepository} the workflow admin
 * itself uses, whatever backs it: the file directory in file mode, the
 * database in Postgres mode. A workflow the user already has under that id
 * is left alone.
 */
@Component
public class InitialWorkflowLoader implements ApplicationRunner {

    private static final String LOCATION = "classpath:initial-data/workflows/*.json";

    private final WorkflowDataRepository workflows;
    private final JacksonWorkflowSerializer serializer =
            new JacksonWorkflowSerializer(WorkflowObjectMapperFactory.create());

    private final ScopeSupplier scope;
    private final Namespace startupNamespace;

    public InitialWorkflowLoader(WorkflowDataRepository workflows,
                                 org.springframework.beans.factory.ObjectProvider<ScopeSupplier> scope,
                                 @Value("${mindconnect.namespace:local}") String startupNamespace) {
        this.workflows = workflows;
        this.scope = scope.getIfAvailable();
        this.startupNamespace = new Namespace(startupNamespace);
    }

    /** Seeds run in the default namespace: the main thread binds no scope of its own. */
    @Override
    public void run(ApplicationArguments args) {
        StartupScope.run(scope, startupNamespace, this::seed);
    }

    private void seed() {
        new ImportInitialDataInstaller(
                workflows::exists,
                (id, resource) -> {
                    try (InputStream in = resource.getInputStream()) {
                        workflows.save(id, serializer.read(in));
                    }
                }).install(LOCATION);
    }
}
