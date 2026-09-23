package ai.mindconnect.extension.adapter.classpath;

import ai.mindconnect.extension.domain.Extension;
import ai.mindconnect.extension.domain.ExtensionManifest;
import ai.mindconnect.extension.domain.ExtensionRegistry;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Reads every {@code META-INF/mindconnect/extension.json} the class loader
 * can see — one per jar that is an extension — into an
 * {@link ExtensionRegistry}. Runs once, at start, before anything is wired
 * from the classpath: the manifests are what the host wires by.
 *
 * <p>A manifest that cannot be read is a {@link ExtensionRegistry.Problem}
 * naming the resource, not an exception: whether the host starts anyway is
 * the host's decision (strict or not), and the Extensions screen shows it
 * either way.
 */
public final class ClasspathManifests {

    private static final Logger log = LoggerFactory.getLogger(ClasspathManifests.class);

    /** Where a jar keeps its manifest. */
    public static final String RESOURCE = "META-INF/mindconnect/extension.json";

    private ClasspathManifests() {
    }

    /** The manifests on the given class loader's classpath, read with a mapper that ignores what it does not know. */
    public static ExtensionRegistry load(ClassLoader classLoader) {
        return load(classLoader, JsonMapper.builder()
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .build());
    }

    public static ExtensionRegistry load(ClassLoader classLoader, ObjectMapper mapper) {
        List<URL> resources;
        try {
            resources = Collections.list(classLoader.getResources(RESOURCE));
        } catch (IOException e) {
            return new ExtensionRegistry(List.of(),
                    List.of(new ExtensionRegistry.Problem(null, "classpath cannot be listed: " + e)));
        }
        List<Extension> found = new ArrayList<>();
        List<ExtensionRegistry.Problem> problems = new ArrayList<>();
        for (URL url : resources) {
            String origin = originOf(url);
            try (InputStream in = url.openStream()) {
                ExtensionManifest manifest = mapper.readValue(in, ExtensionManifest.class);
                found.add(new Extension(manifest, origin));
                log.info("Extension '{}' {} ({}) from {}", manifest.id(), manifest.version(),
                        manifest.runtime().wireName(), origin);
            } catch (IOException | RuntimeException e) {
                problems.add(new ExtensionRegistry.Problem(null, origin + ": manifest unreadable — " + e.getMessage()));
                log.warn("Extension manifest {} is unreadable: {}", url, e.toString());
            }
        }
        return new ExtensionRegistry(found, problems);
    }

    /**
     * What to call the place a manifest came from: the jar's file name for
     * {@code jar:file:/…/acme-crm-1.4.0.jar!/META-INF/…}, the directory for a
     * manifest read from exploded classes.
     */
    static String originOf(URL url) {
        String text = url.toString();
        int bang = text.indexOf("!/");
        if (bang > 0) {
            String jar = text.substring(0, bang);
            return jar.substring(jar.lastIndexOf('/') + 1);
        }
        int end = text.indexOf(RESOURCE);
        String dir = end > 0 ? text.substring(0, end) : text;
        if (dir.endsWith("/")) dir = dir.substring(0, dir.length() - 1);
        return dir.substring(dir.lastIndexOf('/') + 1);
    }
}
