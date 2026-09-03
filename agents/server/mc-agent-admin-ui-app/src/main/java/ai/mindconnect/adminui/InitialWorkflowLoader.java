package ai.mindconnect.adminui;

import ai.mindconnect.initialdata.ImportInitialDataInstaller;
import ai.mindconnect.workflow.jackson.JacksonWorkflowSerializer;
import ai.mindconnect.workflow.jackson.WorkflowObjectMapperFactory;
import ai.mindconnect.workflow.persistence.port.WorkflowDataRepository;
import org.springframework.boot.ApplicationArguments;
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

    public InitialWorkflowLoader(WorkflowDataRepository workflows) {
        this.workflows = workflows;
    }

    @Override
    public void run(ApplicationArguments args) {
        new ImportInitialDataInstaller(
                workflows::exists,
                (id, resource) -> {
                    try (InputStream in = resource.getInputStream()) {
                        workflows.save(id, serializer.read(in));
                    }
                }).install(LOCATION);
    }
}
