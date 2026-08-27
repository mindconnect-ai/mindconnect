package ai.mindconnect.initialdata;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;

import java.util.ArrayList;
import java.util.List;

/**
 * Seeds an application from bundled classpath resources on first run.
 *
 * <p>The shared part — scanning a {@code classpath:} pattern and skipping
 * entries that already exist — lives here as {@link #install(String)}. A
 * concrete installer only decides two things per resource: whether it already
 * {@link #exists(String) exists}, and how to {@link #install(String, Resource)
 * install} it. That keeps the file-copy and DB-import strategies tiny and lets
 * apps add their own (S3, JPA, …).
 *
 * <p><b>id</b> is the resource's file name without its extension — e.g.
 * {@code initial-data/workflows/hello.json} → {@code "hello"}.
 */
public interface InitialDataInstaller {

    Logger log = LoggerFactory.getLogger(InitialDataInstaller.class);

    /** Whether an entry with this id is already present in the target. */
    boolean exists(String id);

    /** Install one resource under {@code id} (copy a file, import a row, …). */
    void install(String id, Resource resource) throws Exception;

    /**
     * Scans {@code locationPattern} (e.g.
     * {@code "classpath:initial-data/workflows/*.json"}) and installs every
     * resource whose id is not yet {@link #exists present}.
     *
     * @return the ids that were newly installed, in scan order
     */
    default List<String> install(String locationPattern) {
        ResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        List<String> installed = new ArrayList<>();
        Resource[] resources;
        try {
            resources = resolver.getResources(locationPattern);
        } catch (Exception e) {
            log.warn("Cannot scan initial data at {}: {}", locationPattern, e.getMessage());
            return installed;
        }
        for (Resource resource : resources) {
            String id = stripExtension(resource.getFilename());
            if (id == null || exists(id)) {
                continue; // keep what the user already has
            }
            try {
                install(id, resource);
                installed.add(id);
                log.info("Installed initial data '{}'", id);
            } catch (Exception e) {
                log.warn("Could not install initial data '{}' from {}: {}",
                        id, resource.getFilename(), e.getMessage());
            }
        }
        return installed;
    }

    /** File name without its extension; null-safe. */
    static String stripExtension(String filename) {
        if (filename == null) return null;
        int dot = filename.lastIndexOf('.');
        return dot > 0 ? filename.substring(0, dot) : filename;
    }
}
