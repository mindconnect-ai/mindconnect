package ai.mindconnect.extension.domain;

import ai.mindconnect.extension.domain.Extension;
import ai.mindconnect.extension.domain.ExtensionId;
import ai.mindconnect.extension.domain.ExtensionManifest;
import ai.mindconnect.extension.domain.ExtensionRegistry;
import ai.mindconnect.extension.domain.ExtensionRuntime;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExtensionRegistryTest {

    private static Extension jar(String id, String origin, ExtensionManifest.Contributes contributes,
                                 ExtensionManifest.Requires requires) {
        return new Extension(new ExtensionManifest(ExtensionId.of(id), null, "1.0", null, null,
                ExtensionRuntime.JAR, requires, null, null, contributes), origin);
    }

    private static ExtensionManifest.Contributes replaces(String... seams) {
        return new ExtensionManifest.Contributes(null, null, null, null, null, null, List.of(seams), null);
    }

    @Test
    void keeps_the_first_of_two_manifests_with_one_id_and_reports_the_second() {
        var registry = new ExtensionRegistry(List.of(
                jar("acme", "acme-1.jar", null, null), jar("acme", "acme-2.jar", null, null)));

        assertThat(registry.all()).hasSize(1);
        assertThat(registry.find(ExtensionId.of("acme")).orElseThrow().origin()).isEqualTo("acme-1.jar");
        assertThat(registry.problems()).singleElement().satisfies(problem -> {
            assertThat(problem.id()).isEqualTo(ExtensionId.of("acme"));
            assertThat(problem.message()).contains("declared twice").contains("acme-2.jar");
        });
    }

    @Test
    void two_extensions_replacing_one_seam_is_a_problem() {
        var registry = new ExtensionRegistry(List.of(
                jar("ext-a", "a.jar", replaces("Persistence"), null),
                jar("ext-b", "b.jar", replaces("Persistence"), null)));

        assertThat(registry.all()).hasSize(2);
        assertThat(registry.problems()).singleElement().satisfies(problem -> {
            assertThat(problem.id()).isEqualTo(ExtensionId.of("ext-b"));
            assertThat(problem.message()).contains("Persistence").contains("ext-a already replaces");
        });
    }

    @Test
    void a_required_extension_that_is_missing_is_a_problem_an_optional_one_is_not() {
        var needsMail = new ExtensionManifest.Requires(null, null, List.of(
                new ExtensionManifest.Requires.Dependency(ExtensionId.of("mc-mail"), false)));
        var likesMail = new ExtensionManifest.Requires(null, null, List.of(
                new ExtensionManifest.Requires.Dependency(ExtensionId.of("mc-mail"), true)));

        assertThat(new ExtensionRegistry(List.of(jar("ext-a", "a.jar", null, needsMail))).problems())
                .singleElement().extracting(ExtensionRegistry.Problem::message).asString()
                .contains("requires mc-mail");
        assertThat(new ExtensionRegistry(List.of(jar("ext-a", "a.jar", null, likesMail))).problems()).isEmpty();
    }

    @Test
    void a_remote_manifest_on_the_classpath_is_a_problem() {
        var remote = new Extension(new ExtensionManifest(ExtensionId.of("saas"), null, null, null, null,
                ExtensionRuntime.REMOTE, null, null, null, null), "saas.jar");

        assertThat(new ExtensionRegistry(List.of(remote)).problems())
                .singleElement().extracting(ExtensionRegistry.Problem::message).asString()
                .contains("runtime remote");
    }

    @Test
    void load_problems_are_kept_beside_the_registry_s_own() {
        var registry = new ExtensionRegistry(List.of(),
                List.of(new ExtensionRegistry.Problem(null, "x.jar: manifest unreadable")));

        assertThat(registry.isEmpty()).isTrue();
        assertThat(registry.hasProblems()).isTrue();
        assertThat(registry.problems().get(0).toString()).isEqualTo("x.jar: manifest unreadable");
    }

    @Test
    void an_id_has_a_form() {
        assertThatThrownBy(() -> ExtensionId.of("Acme")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ExtensionId.of("a")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ExtensionId.of("-acme")).isInstanceOf(IllegalArgumentException.class);
        assertThat(ExtensionId.of("acme-crm").value()).isEqualTo("acme-crm");
    }

    @Test
    void a_manifest_fills_in_what_it_does_not_say() {
        var manifest = new ExtensionManifest(ExtensionId.of("acme"), null, null, null, null, null, null, null, null, null);

        assertThat(manifest.name()).isEqualTo("acme");
        assertThat(manifest.version()).isEqualTo("0");
        assertThat(manifest.runtime()).isEqualTo(ExtensionRuntime.JAR);
        assertThat(manifest.isEnabledByDefault()).isTrue();
        assertThat(manifest.contributes().tools().isEmpty()).isTrue();
        assertThat(manifest.contributes().ui().menu()).isEmpty();
        assertThat(manifest.vendor().label()).isEmpty();
    }
}
