package ai.mindconnect.extension.service;

import ai.mindconnect.agent.UserId;
import ai.mindconnect.extension.domain.Extension;
import ai.mindconnect.extension.domain.ExtensionActivation;
import ai.mindconnect.extension.domain.ExtensionId;
import ai.mindconnect.extension.domain.ExtensionManifest;
import ai.mindconnect.extension.domain.ExtensionRegistry;
import ai.mindconnect.extension.port.out.ExtensionActivationRepository;
import ai.mindconnect.extension.service.ExtensionService;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExtensionServiceTest {

    /** The port in a map — the core module has no adapter of its own. */
    static final class MapRepository implements ExtensionActivationRepository {
        final Map<ExtensionId, ExtensionActivation> decisions = new HashMap<>();
        @Override public Optional<ExtensionActivation> find(ExtensionId id) { return Optional.ofNullable(decisions.get(id)); }
        @Override public List<ExtensionActivation> all() { return List.copyOf(decisions.values()); }
        @Override public void save(ExtensionActivation activation) { decisions.put(activation.extensionId(), activation); }
        @Override public void delete(ExtensionId id) { decisions.remove(id); }
    }

    private static final ExtensionId ACME = ExtensionId.of("acme-crm");
    private static final ExtensionId OPT_IN = ExtensionId.of("opt-in");

    private final MapRepository decisions = new MapRepository();
    private final ExtensionService service = new ExtensionService(new ExtensionRegistry(List.of(
            new Extension(new ExtensionManifest(ACME, "Acme CRM", "1.0", null, null, null, null, true, null,
                    new ExtensionManifest.Contributes(
                            new ExtensionManifest.Tools(List.of("ai.acme.CrmTools"), List.of("acme_*", "crm_export")),
                            null, null,
                            new ExtensionManifest.Ui(List.of(
                                    new ExtensionManifest.Ui.MenuEntry("nav-acme", "Acme", "/admin/acme", null, null, null, null)),
                                    List.of(new ExtensionManifest.Ui.Route("/admin/acme/**", List.of("ADMIN")),
                                            new ExtensionManifest.Ui.Route("/admin/acme/mine/**", List.of("USER"))),
                                    null),
                            null, null, null, null)), "acme.jar"),
            new Extension(new ExtensionManifest(OPT_IN, null, null, null, null, null, null, false, null, null), "opt.jar"))),
            decisions);

    @Test
    void without_a_decision_the_manifest_s_default_holds() {
        assertThat(service.isEnabled(ACME)).isTrue();
        assertThat(service.isEnabled(OPT_IN)).isFalse();
        assertThat(service.find(ACME).orElseThrow().isDefault()).isTrue();
        assertThat(service.isEnabled(ExtensionId.of("nobody"))).isFalse();
    }

    @Test
    void a_decision_wins_over_the_default_and_can_be_forgotten() {
        service.disable(ACME, UserId.of("david"));
        assertThat(service.isEnabled(ACME)).isFalse();
        assertThat(service.find(ACME).orElseThrow().decision()).isPresent()
                .get().extracting(ExtensionActivation::changedBy).isEqualTo(UserId.of("david"));

        service.enable(OPT_IN, null);
        assertThat(service.isEnabled(OPT_IN)).isTrue();

        service.reset(ACME);
        assertThat(service.isEnabled(ACME)).isTrue();
        assertThat(service.find(ACME).orElseThrow().isDefault()).isTrue();
    }

    @Test
    void deciding_about_an_unknown_extension_is_refused() {
        assertThatThrownBy(() -> service.disable(ExtensionId.of("nobody"), null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("nobody");
    }

    @Test
    void a_switched_off_extension_hides_the_tools_and_menu_entries_it_declared() {
        assertThat(service.hidesTool("acme_contacts")).isFalse();

        service.disable(ACME, null);

        assertThat(service.hidesTool("acme_contacts")).isTrue();
        assertThat(service.hidesTool("crm_export")).isTrue();
        assertThat(service.hidesTool("crm_export_all")).isFalse();
        assertThat(service.hidesTool("bash")).isFalse();
        assertThat(service.hidesMenuEntry("nav-acme")).isTrue();
        assertThat(service.hidesMenuEntry("nav-agents")).isFalse();
    }

    @Test
    void patterns_are_literal_but_for_the_star() {
        assertThat(ExtensionService.matches("acme_*", "acme_x")).isTrue();
        assertThat(ExtensionService.matches("acme_*", "acme_")).isTrue();
        assertThat(ExtensionService.matches("acme_*", "acmex")).isFalse();
        assertThat(ExtensionService.matches("*_export", "crm_export")).isTrue();
        assertThat(ExtensionService.matches("a.b", "axb")).isFalse();
        assertThat(ExtensionService.matches("exact", "exact")).isTrue();
    }

    @Test
    void the_list_carries_every_extension_with_its_state() {
        service.disable(ACME, null);
        assertThat(service.list()).extracting(s -> s.id().value() + "=" + s.enabled())
                .containsExactly("acme-crm=false", "opt-in=false");
    }

    @Test
    void a_route_the_manifest_opens_to_users_is_open_only_while_the_extension_is_on() {
        assertThat(service.opensToUsers("/admin/acme/mine")).isTrue();
        assertThat(service.opensToUsers("/admin/acme/mine/export")).isTrue();
        assertThat(service.opensToUsers("/admin/acme")).as("the admin's route of the same extension").isFalse();
        assertThat(service.opensToUsers("/admin/agents")).as("nobody's route").isFalse();

        service.disable(ACME, null);

        assertThat(service.opensToUsers("/admin/acme/mine")).isFalse();
    }
}
