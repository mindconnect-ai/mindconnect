package ai.mindconnect.extension.domain;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ExtensionRouteTest {

    private static final ExtensionManifest.Ui.Route ROUTE = new ExtensionManifest.Ui.Route("/admin/acme/**", null);

    @Test
    void a_route_is_a_prefix_pattern() {
        assertThat(ROUTE.prefix()).isEqualTo("/admin/acme");
        assertThat(new ExtensionManifest.Ui.Route("/admin/acme/", null).prefix()).isEqualTo("/admin/acme");
        assertThat(new ExtensionManifest.Ui.Route("/admin/acme", null).prefix()).isEqualTo("/admin/acme");

        assertThat(ROUTE.covers("/admin/acme")).isTrue();
        assertThat(ROUTE.covers("/admin/acme/ask")).isTrue();
        assertThat(ROUTE.covers("/admin/acmeville")).isFalse();
        assertThat(ROUTE.covers("/admin")).isFalse();
        assertThat(ROUTE.covers(null)).isFalse();
    }

    @Test
    void the_registry_names_the_owner_of_a_path() {
        var acme = new Extension(new ExtensionManifest(ExtensionId.of("acme-crm"), null, null, null, null, null, null,
                null, null, new ExtensionManifest.Contributes(null, null, null,
                new ExtensionManifest.Ui(null, List.of(ROUTE), null), null, null, null, null)), "acme.jar");
        var registry = new ExtensionRegistry(List.of(acme));

        assertThat(registry.routeOwner("/admin/acme/ask")).isPresent().get().extracting(Extension::id)
                .isEqualTo(ExtensionId.of("acme-crm"));
        assertThat(registry.routeOwner("/admin/agents")).isEmpty();
    }
}
