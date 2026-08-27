package ai.mindconnect.adminui;

import ai.mindconnect.initialdata.FileCopyInitialDataInstaller;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

/**
 * Seeds the bundled example workflows on startup — same pattern as the
 * standalone workflow admin app, and the same skip-if-present semantics as
 * {@link InitialDataLoader} uses for LLM configs and agent definitions.
 *
 * <p>Copies {@code classpath:initial-data/workflows/*.json} into the directory
 * the embedded workflow admin reads from, skipping any workflow the user
 * already has.
 */
@Component
public class InitialWorkflowLoader implements ApplicationRunner {

    private static final String LOCATION = "classpath:initial-data/workflows/*.json";

    private final String storeDir;

    public InitialWorkflowLoader(
            @Value("${mindconnect.workflow-admin.dir:data/workflows}") String storeDir) {
        this.storeDir = storeDir;
    }

    @Override
    public void run(ApplicationArguments args) {
        new FileCopyInitialDataInstaller(Path.of(storeDir)).install(LOCATION);
    }
}
