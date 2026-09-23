package ai.mindconnect.extension.adapter.classpath;

import ai.mindconnect.extension.domain.Extension;
import ai.mindconnect.extension.domain.ExtensionId;
import ai.mindconnect.extension.domain.ExtensionManifest;
import ai.mindconnect.extension.domain.ExtensionRegistry;
import ai.mindconnect.extension.domain.ExtensionRuntime;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ClasspathManifestsTest {

    @Test
    void reads_the_manifest_on_the_test_classpath_and_ignores_what_it_does_not_know() {
        ExtensionRegistry registry = ClasspathManifests.load(getClass().getClassLoader());

        Extension extension = registry.find(ExtensionId.of("test-extension")).orElseThrow();
        ExtensionManifest manifest = extension.manifest();
        assertThat(manifest.name()).isEqualTo("Test Extension");
        assertThat(manifest.version()).isEqualTo("1.2.3");
        assertThat(manifest.runtime()).isEqualTo(ExtensionRuntime.JAR);
        assertThat(manifest.vendor().label()).isEqualTo("Mindconnect");
        assertThat(manifest.requires().mindconnect()).isEqualTo(">=0.8");
        assertThat(manifest.requires().extensions()).singleElement()
                .satisfies(dep -> assertThat(dep.isOptional()).isTrue());
        assertThat(manifest.permissions()).containsExactly("tools:call");
        assertThat(manifest.contributes().tools().names()).containsExactly("test_*");
        assertThat(manifest.contributes().content().agents()).containsExactly("test-agent");
        assertThat(manifest.contributes().ui().menu()).singleElement()
                .satisfies(entry -> assertThat(entry.group()).isEqualTo("nav-group-tools"));
        assertThat(manifest.contributes().ui().routes().get(0).roles()).containsExactly("ADMIN");
        assertThat(manifest.contributes().persistence().schema()).isEqualTo("ext_test");
        assertThat(extension.origin()).isEqualTo("test-classes");
        assertThat(registry.problems()).isEmpty();
    }

    @Test
    void an_unreadable_manifest_is_a_problem_not_an_exception(@org.junit.jupiter.api.io.TempDir Path dir) throws Exception {
        Path manifest = dir.resolve("broken").resolve(ClasspathManifests.RESOURCE);
        Files.createDirectories(manifest.getParent());
        Files.writeString(manifest, "{ \"id\": \"Broken Id\" }");
        try (var loader = new URLClassLoader(new URL[]{dir.resolve("broken").toUri().toURL()}, null)) {
            ExtensionRegistry registry = ClasspathManifests.load(loader);

            assertThat(registry.isEmpty()).isTrue();
            assertThat(registry.problems()).singleElement().satisfies(problem -> {
                assertThat(problem.id()).isNull();
                assertThat(problem.message()).startsWith("broken: manifest unreadable");
            });
        }
    }

    @Test
    void the_origin_is_the_jar_s_file_name_or_the_directory() throws Exception {
        assertThat(ClasspathManifests.originOf(URI.create(
                "jar:file:/opt/app/lib/acme-crm-1.4.0.jar!/META-INF/mindconnect/extension.json").toURL()))
                .isEqualTo("acme-crm-1.4.0.jar");
        assertThat(ClasspathManifests.originOf(URI.create(
                "file:/work/acme/target/classes/META-INF/mindconnect/extension.json").toURL()))
                .isEqualTo("classes");
    }
}
