package ai.mindconnect.mcp.gateway.local;

import ai.mindconnect.initialdata.FileCopyInitialDataInstaller;
import ai.mindconnect.initialdata.ImportInitialDataInstaller;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.InputStream;

/**
 * The registrations an installation ships, put into a store at start-up.
 * Only the default namespace gets them: a namespace somebody creates starts
 * empty. Existing ones are never overwritten — an operator who changed or
 * deleted a bundled server meant to.
 */
public final class BundledMcpRegistrations {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private BundledMcpRegistrations() {
    }

    /**
     * Puts the registrations matching {@code initialData} (a classpath pattern) into
     * {@code repository}. Files are copied as they are; any other store gets them
     * read and saved.
     */
    public static void seed(McpServerRepository repository, String initialData) {
        if (repository instanceof FileMcpServerRepository files) {
            new FileCopyInitialDataInstaller(files.directory()).install(initialData);
            return;
        }
        new ImportInitialDataInstaller(
                id -> repository.findAll().stream().anyMatch(r -> r.id().value().equals(id)),
                (id, resource) -> {
                    try (InputStream in = resource.getInputStream()) {
                        repository.save(McpRegistrationJson.read(MAPPER.readTree(in), resource.getDescription()));
                    }
                }).install(initialData);
    }
}
