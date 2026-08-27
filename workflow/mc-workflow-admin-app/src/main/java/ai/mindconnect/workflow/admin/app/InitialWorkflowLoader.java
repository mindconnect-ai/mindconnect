package ai.mindconnect.workflow.admin.app;

import ai.mindconnect.initialdata.FileCopyInitialDataInstaller;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

/**
 * Seeds the bundled example workflows on startup.
 *
 * <p>Copies {@code classpath:initial-data/workflows/*.json} into the same
 * directory the file-backed store reads from, skipping any workflow the user
 * already has. The generic copying logic lives in {@code mc-initial-data}; this
 * just points it at the configured store directory.
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
