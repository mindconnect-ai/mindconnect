package ai.mindconnect.arch;

import com.tngtech.archunit.core.importer.Location;
import com.tngtech.archunit.junit.LocationProvider;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The compiled classes of every module of one area, found through the area's
 * aggregator POM, so that the arch-tests module needs no dependency on the
 * modules it checks and the list can never go stale.
 *
 * <p>Configured by {@code arch-area.properties} on the test classpath:
 * {@code aggregator} is the POM whose {@code <module>} entries define the area
 * (relative to the test module), {@code include} an optional path prefix the
 * entries must start with (used by the common area, whose modules are listed in
 * the root POM). Entries pointing outside the aggregator's directory
 * ({@code ../common/...}) belong to another area and are skipped, as are POM
 * packaged modules, modules without main sources and the arch-tests module itself.
 *
 * <p>A listed module without {@code target/classes} fails the run: a vacuous
 * pass over missing classes must never look green.
 */
public final class AreaLocations implements LocationProvider {

    private static final Pattern MODULE = Pattern.compile("<module>\\s*([^<\\s]+)\\s*</module>");
    private static final Pattern POM_PACKAGING = Pattern.compile("<packaging>\\s*pom\\s*</packaging>");

    @Override
    public Set<Location> get(Class<?> testClass) {
        Properties config = load("/arch-area.properties");
        Path testModule = Path.of("").toAbsolutePath();
        Path aggregator = testModule.resolve(config.getProperty("aggregator")).normalize();
        String include = config.getProperty("include", "");
        Set<Location> locations = new HashSet<>();
        try {
            Matcher modules = MODULE.matcher(Files.readString(aggregator));
            while (modules.find()) {
                String entry = modules.group(1);
                if (entry.startsWith("../") || !entry.startsWith(include)) {
                    continue;
                }
                Path module = aggregator.getParent().resolve(entry).normalize();
                if (module.equals(testModule) || isPomPackaged(module)) {
                    continue;
                }
                if (!Files.isDirectory(module.resolve("src/main/java"))) {
                    continue; // resources-only module, nothing to analyse
                }
                Path classes = module.resolve("target/classes");
                if (!Files.isDirectory(classes)) {
                    throw new IllegalStateException("No compiled classes at " + classes
                        + " — build the area first (mvn -f " + aggregator + " install -DskipTests)");
                }
                locations.add(Location.of(classes));
            }
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read aggregator POM " + aggregator, e);
        }
        if (locations.isEmpty()) {
            throw new IllegalStateException("No modules found in " + aggregator + " with include=" + include);
        }
        return locations;
    }

    private static boolean isPomPackaged(Path module) throws IOException {
        Path pom = module.resolve("pom.xml");
        return Files.exists(pom) && POM_PACKAGING.matcher(Files.readString(pom)).find();
    }

    private static Properties load(String resource) {
        Properties properties = new Properties();
        try (InputStream in = AreaLocations.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("Missing " + resource + " on the test classpath");
            }
            properties.load(in);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read " + resource, e);
        }
        return properties;
    }
}
