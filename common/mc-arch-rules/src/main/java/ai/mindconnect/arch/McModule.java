package ai.mindconnect.arch;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.Source;

import java.net.URI;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Which Maven module a class was compiled in, read off where its class file
 * sits: {@code <module>/target/classes/...} inside the reactor, or
 * {@code .../ai/mindconnect/<artifactId>/<version>/<artifactId>-<version>.jar!/...}
 * in the local repository. The conventions speak of modules ("a -core module
 * imports no framework"); ArchUnit speaks of classes, so this is the bridge.
 */
public final class McModule {

    private static final Pattern REPOSITORY_JAR =
        Pattern.compile("/ai/mindconnect/([^/]+)/[^/]+/\\1-[^/]+\\.jar!");

    private McModule() {
    }

    public static Optional<String> of(JavaClass javaClass) {
        return javaClass.getSource().map(Source::getUri).flatMap(McModule::of);
    }

    public static Optional<String> of(URI uri) {
        String location = uri.toString();
        int target = location.indexOf("/target/classes/");
        if (target > 0) {
            String moduleDir = location.substring(0, target);
            return Optional.of(moduleDir.substring(moduleDir.lastIndexOf('/') + 1));
        }
        Matcher jar = REPOSITORY_JAR.matcher(location);
        return jar.find() ? Optional.of(jar.group(1)) : Optional.empty();
    }

    /** {@code -core} modules and the two engines: the places that know no framework. */
    public static boolean isCore(String artifactId) {
        return artifactId.endsWith("-core")
            || artifactId.equals("mc-workflow")
            || artifactId.equals("mc-task-queue");
    }

    /** Where Spring wiring may live: starters, deployable apps, REST and UI libraries, demos. */
    public static boolean mayCarrySpringWiring(String artifactId) {
        return artifactId.startsWith("mc-agent-starter-")
            || artifactId.endsWith("-app")
            || artifactId.endsWith("-rest")
            || artifactId.contains("-demo")
            || artifactId.contains("-ui-")
            || artifactId.endsWith("-ui");
    }

    public static DescribedPredicate<JavaClass> inModuleWhere(String description, Predicate<String> artifactId) {
        return DescribedPredicate.describe(description,
            javaClass -> of(javaClass).map(artifactId::test).orElse(false));
    }
}
