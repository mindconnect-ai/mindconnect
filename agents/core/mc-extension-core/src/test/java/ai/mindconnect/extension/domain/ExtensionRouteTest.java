package ai.mindconnect.extension.domain;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ExtensionRouteTest {

    private static final ExtensionManifest.Ui.Route ROUTE = new ExtensionManifest.Ui.Route("/admin/acme-crm/**", null);

    @Test
    void a_route_is_a_prefix_pattern() {
        assertThat(ROUTE.prefix()).isEqualTo("/admin/acme-crm");
        assertThat(new ExtensionManifest.Ui.Route("/admin/acme/", null).prefix()).isEqualTo("/admin/acme");
        assertThat(new ExtensionManifest.Ui.Route("/admin/acme", null).prefix()).isEqualTo("/admin/acme");

        assertThat(ROUTE.covers("/admin/acme-crm")).isTrue();
        assertThat(ROUTE.covers("/admin/acme-crm/ask")).isTrue();
        assertThat(ROUTE.covers("/admin/acme-crmville")).isFalse();
        assertThat(ROUTE.covers("/admin")).isFalse();
        assertThat(ROUTE.covers(null)).isFalse();
    }

    @Test
    void the_registry_names_the_owner_of_a_path() {
        var acme = new Extension(new ExtensionManifest(ExtensionId.of("acme-crm"), null, null, null, null, null, null,
                null, null, new ExtensionManifest.Contributes(null, null, null,
                new ExtensionManifest.Ui(null, List.of(ROUTE), null), null, null, null, null)), "acme.jar");
        var registry = new ExtensionRegistry(List.of(acme));

        assertThat(registry.routeOwner("/admin/acme-crm/ask")).isPresent().get().extracting(Extension::id)
                .isEqualTo(ExtensionId.of("acme-crm"));
        assertThat(registry.routeOwner("/admin/agents")).isEmpty();
    }

    @Test
    void a_route_belongs_under_the_extension_s_own_prefix_or_it_is_ignored() {
        ExtensionId acme = ExtensionId.of("acme-crm");
        assertThat(new ExtensionManifest.Ui.Route("/admin/acme-crm/**", null).isOwnedBy(acme)).isTrue();
        assertThat(new ExtensionManifest.Ui.Route("/ext/acme-crm/api/**", null).isOwnedBy(acme)).isTrue();
        assertThat(new ExtensionManifest.Ui.Route("/admin/acme-crm-extra/**", null).isOwnedBy(acme)).isFalse();
        assertThat(new ExtensionManifest.Ui.Route("/chat/**", null).isOwnedBy(acme)).isFalse();
        assertThat(new ExtensionManifest.Ui.Route("/**", null).isOwnedBy(acme)).isFalse();

        var grabby = new Extension(new ExtensionManifest(acme, null, null, null, null, null, null, null, null,
                new ExtensionManifest.Contributes(null, null, null, new ExtensionManifest.Ui(null, List.of(
                        new ExtensionManifest.Ui.Route("/chat/**", null),
                        new ExtensionManifest.Ui.Route("/admin/acme-crm/**", List.of("USER"))), null),
                        null, null, null, null)), "acme.jar");
        var registry = new ExtensionRegistry(List.of(grabby));

        assertThat(registry.routeOwner("/chat/abc")).isEmpty();
        assertThat(registry.problems()).singleElement().extracting(ExtensionRegistry.Problem::message).asString()
                .contains("/chat/**").contains("ignored");
        assertThat(registry.routeFor("/admin/acme-crm/x").orElseThrow().route().forUsers()).isTrue();
    }
}
