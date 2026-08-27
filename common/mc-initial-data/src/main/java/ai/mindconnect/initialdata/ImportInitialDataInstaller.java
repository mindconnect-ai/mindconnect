package ai.mindconnect.initialdata;

import org.springframework.core.io.Resource;

import java.util.function.Predicate;

/**
 * {@link InitialDataInstaller} for non-file targets (a database, a repository,
 * an in-memory store). The app supplies two callbacks:
 *
 * <ul>
 *   <li>an <b>existence check</b> — does an entry with this id already exist?</li>
 *   <li>an <b>importer</b> — parse the resource and persist it (JSON → object →
 *       {@code repository.save(...)}, say).</li>
 * </ul>
 *
 * <p>Example:
 * <pre>{@code
 * var installer = new ImportInitialDataInstaller(
 *         id -> store.load(id).isPresent(),
 *         (id, res) -> store.save(id, parse(res)));
 * installer.install("classpath:initial-data/workflows/*.json");
 * }</pre>
 */
public class ImportInitialDataInstaller implements InitialDataInstaller {

    /** Parses and persists one bundled resource under {@code id}. */
    @FunctionalInterface
    public interface ResourceImporter {
        void importResource(String id, Resource resource) throws Exception;
    }

    private final Predicate<String> existsCheck;
    private final ResourceImporter importer;

    public ImportInitialDataInstaller(Predicate<String> existsCheck, ResourceImporter importer) {
        this.existsCheck = existsCheck;
        this.importer = importer;
    }

    @Override
    public boolean exists(String id) {
        return existsCheck.test(id);
    }

    @Override
    public void install(String id, Resource resource) throws Exception {
        importer.importResource(id, resource);
    }
}
