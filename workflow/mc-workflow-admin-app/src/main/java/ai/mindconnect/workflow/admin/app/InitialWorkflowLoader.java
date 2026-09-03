package ai.mindconnect.workflow.admin.app;

import ai.mindconnect.initialdata.ImportInitialDataInstaller;
import ai.mindconnect.workflow.jackson.JacksonWorkflowSerializer;
import ai.mindconnect.workflow.jackson.WorkflowObjectMapperFactory;
import ai.mindconnect.workflow.persistence.port.WorkflowDataRepository;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.io.InputStream;

/**
 * Seeds the bundled example workflows on startup, skipping any the user
 * already has under the same id. Goes through the {@link WorkflowDataRepository}
 * the admin uses — the file directory by default, the database with
 * {@code mindconnect.persistence=postgres}.
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
