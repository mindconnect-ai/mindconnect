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
    void the_most_specific_route_answers_for_a_path_and_says_whether_users_may_open_it() {
        var admin = new ExtensionManifest.Ui.Route("/admin/usage/**", List.of("ADMIN"));
        var mine = new ExtensionManifest.Ui.Route("/admin/usage/mine/**", List.of("USER"));
        var ui = new ExtensionManifest.Ui(null, List.of(admin, mine), null);

        assertThat(ui.routeFor("/admin/usage")).contains(admin);
        assertThat(ui.routeFor("/admin/usage/mine")).contains(mine);
        assertThat(ui.routeFor("/admin/usage/mine/export")).contains(mine);
        assertThat(ui.routeFor("/admin/usage/minefield")).contains(admin);
        assertThat(ui.routeFor("/admin/agents")).isEmpty();

        assertThat(admin.forUsers()).isFalse();
        assertThat(mine.forUsers()).isTrue();
        assertThat(new ExtensionManifest.Ui.Route("/admin/x/**", List.of("admin", "user")).forUsers()).isTrue();
        assertThat(ROUTE.forUsers()).as("no roles: an admin's").isFalse();
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

    @Test
    void an_extension_s_rest_api_under_api_id_is_a_home_of_its_own() {
        ExtensionId acme = ExtensionId.of("acme-crm");
        assertThat(ExtensionManifest.Ui.Route.homes(acme))
                .containsExactly("/admin/acme-crm", "/ext/acme-crm", "/api/acme-crm");
        assertThat(new ExtensionManifest.Ui.Route("/api/acme-crm/**", null).isOwnedBy(acme)).isTrue();
        assertThat(new ExtensionManifest.Ui.Route("/api/acme-crm", null).isOwnedBy(acme)).isTrue();
        assertThat(new ExtensionManifest.Ui.Route("/api/acme-crm/jobs/**", null).isOwnedBy(acme)).isTrue();
        assertThat(new ExtensionManifest.Ui.Route("/api/acme-crm-extra/**", null).isOwnedBy(acme)).isFalse();
        assertThat(new ExtensionManifest.Ui.Route("/api/jobs/**", null).isOwnedBy(acme))
                .as("the shared /api space is nobody's").isFalse();
        assertThat(new ExtensionManifest.Ui.Route("/api/**", null).isOwnedBy(acme)).isFalse();
        assertThat(new ExtensionManifest.Ui.Route("/v1/acme-crm/**", null).isOwnedBy(acme)).isFalse();
    }

    @Test
    void a_route_under_api_is_an_api_and_not_a_screen() {
        assertThat(new ExtensionManifest.Ui.Route("/api/acme-crm/**", null).isApi()).isTrue();
        assertThat(new ExtensionManifest.Ui.Route("/api/acme-crm", null).isApi()).isTrue();
        assertThat(new ExtensionManifest.Ui.Route("/admin/acme-crm/**", null).isApi()).isFalse();
        assertThat(new ExtensionManifest.Ui.Route("/ext/acme-crm/api/**", null).isApi())
                .as("an API path below a screen home is still that home's").isFalse();
        assertThat(new ExtensionManifest.Ui.Route("/apis/acme-crm/**", null).isApi()).isFalse();
    }

    @Test
    void the_registry_owns_api_routes_but_names_only_screens_as_pages() {
        ExtensionId acme = ExtensionId.of("acme-crm");
        var extension = new Extension(new ExtensionManifest(acme, null, null, null, null, null, null, null, null,
                new ExtensionManifest.Contributes(null, null, null, new ExtensionManifest.Ui(null, List.of(
                        new ExtensionManifest.Ui.Route("/ext/acme-crm/**", List.of("USER")),
                        new ExtensionManifest.Ui.Route("/api/acme-crm/**", List.of("ADMIN", "USER")),
                        new ExtensionManifest.Ui.Route("/api/jobs/**", List.of("USER"))), null),
                        null, null, null, null)), "acme.jar");
        var registry = new ExtensionRegistry(List.of(extension));

        assertThat(registry.routeOwner("/api/acme-crm/jobs/42")).isPresent();
        assertThat(registry.routeFor("/api/acme-crm/jobs").orElseThrow().route().forUsers()).isTrue();
        assertThat(registry.pageOwner("/api/acme-crm/jobs/42")).as("JSON for scripts, not a page").isEmpty();
        assertThat(registry.pageOwner("/ext/acme-crm/board")).isPresent();
        assertThat(registry.routeOwner("/api/jobs")).isEmpty();
        assertThat(registry.problems()).singleElement().extracting(ExtensionRegistry.Problem::message).asString()
                .isEqualTo("route /api/jobs/** is not under /admin/acme-crm/, /ext/acme-crm/ or /api/acme-crm/"
                        + " — ignored");
    }
}
