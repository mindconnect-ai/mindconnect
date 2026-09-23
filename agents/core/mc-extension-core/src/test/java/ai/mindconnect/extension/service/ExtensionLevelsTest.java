package ai.mindconnect.extension.service;

import ai.mindconnect.agent.UserId;
import ai.mindconnect.extension.domain.BrandActivation;
import ai.mindconnect.extension.domain.Extension;
import ai.mindconnect.extension.domain.ExtensionActivation;
import ai.mindconnect.extension.domain.ExtensionId;
import ai.mindconnect.extension.domain.ExtensionManifest;
import ai.mindconnect.extension.domain.ExtensionRegistry;
import ai.mindconnect.extension.port.out.BrandActivationRepository;
import ai.mindconnect.extension.port.out.ExtensionActivationRepository;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The operator, the brand and the namespace decide, in that order of precedence. */
class ExtensionLevelsTest {

    static final class Namespaces implements ExtensionActivationRepository {
        final Map<ExtensionId, ExtensionActivation> decisions = new HashMap<>();
        @Override public Optional<ExtensionActivation> find(ExtensionId id) { return Optional.ofNullable(decisions.get(id)); }
        @Override public List<ExtensionActivation> all() { return List.copyOf(decisions.values()); }
        @Override public void save(ExtensionActivation activation) { decisions.put(activation.extensionId(), activation); }
        @Override public void delete(ExtensionId id) { decisions.remove(id); }
    }

    static final class Brands implements BrandActivationRepository {
        final Map<String, BrandActivation> decisions = new HashMap<>();
        @Override public Optional<BrandActivation> find(String brand, ExtensionId id) { return Optional.ofNullable(decisions.get(brand + "/" + id)); }
        @Override public List<BrandActivation> all(String brand) { return decisions.values().stream().filter(a -> a.brand().equals(brand)).toList(); }
        @Override public void save(BrandActivation activation) { decisions.put(activation.brand() + "/" + activation.extensionId(), activation); }
        @Override public void delete(String brand, ExtensionId id) { decisions.remove(brand + "/" + id); }
    }

    private static final ExtensionId ACME = ExtensionId.of("acme-crm");
    private static final ExtensionId LOCKED_OUT = ExtensionId.of("locked-out");
    private static final UserId DAVID = UserId.of("david");

    private final Namespaces namespaces = new Namespaces();
    private final Brands brands = new Brands();
    private Optional<String> brand = Optional.of("erni");

    private final ExtensionService service = new ExtensionService(new ExtensionRegistry(List.of(
            new Extension(new ExtensionManifest(ACME, "Acme", null, null, null, null, null, true, null, null), "acme.jar"),
            new Extension(new ExtensionManifest(LOCKED_OUT, null, null, null, null, null, null, true, null, null), "x.jar"))),
            namespaces, brands, () -> brand, Set.of("locked-out"));

    @Test
    void the_operator_s_list_wins_over_everything_and_cannot_be_overridden() {
        namespaces.save(ExtensionActivation.of(LOCKED_OUT, true, DAVID));
        brands.save(BrandActivation.of("erni", LOCKED_OUT, true, true, DAVID));

        var status = service.find(LOCKED_OUT).orElseThrow();
        assertThat(status.enabled()).isFalse();
        assertThat(status.origin()).isEqualTo(ExtensionService.Origin.OPERATOR);
        assertThat(status.canDecideHere()).isFalse();
        assertThatThrownBy(() -> service.enable(LOCKED_OUT, DAVID))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("operator");
    }

    @Test
    void a_brand_decision_holds_where_the_namespace_did_not_decide() {
        brands.save(BrandActivation.of("erni", ACME, false, false, DAVID));

        var status = service.find(ACME).orElseThrow();
        assertThat(status.enabled()).isFalse();
        assertThat(status.origin()).isEqualTo(ExtensionService.Origin.BRAND);
        assertThat(status.brand()).contains("erni");
        assertThat(status.canDecideHere()).isTrue();

        service.enable(ACME, DAVID);
        assertThat(service.find(ACME).orElseThrow().origin()).isEqualTo(ExtensionService.Origin.NAMESPACE);
        assertThat(service.isEnabled(ACME)).isTrue();

        service.reset(ACME);
        assertThat(service.find(ACME).orElseThrow().origin()).isEqualTo(ExtensionService.Origin.BRAND);
    }

    @Test
    void a_locked_brand_decision_overrides_the_namespace_and_refuses_its_say() {
        namespaces.save(ExtensionActivation.of(ACME, true, DAVID));
        brands.save(BrandActivation.of("erni", ACME, false, true, DAVID));

        var status = service.find(ACME).orElseThrow();
        assertThat(status.enabled()).isFalse();
        assertThat(status.origin()).isEqualTo(ExtensionService.Origin.BRAND_LOCKED);
        assertThatThrownBy(() -> service.enable(ACME, DAVID))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("locked").hasMessageContaining("erni");
    }

    @Test
    void deciding_for_the_brand_locking_and_resetting() {
        service.decideForBrand(ACME, false, false, DAVID);
        assertThat(service.find(ACME).orElseThrow().origin()).isEqualTo(ExtensionService.Origin.BRAND);

        service.lockForBrand(ACME, true, DAVID);
        assertThat(service.find(ACME).orElseThrow().origin()).isEqualTo(ExtensionService.Origin.BRAND_LOCKED);
        assertThat(service.find(ACME).orElseThrow().brandDecision().orElseThrow().enabled()).isFalse();

        service.lockForBrand(ACME, false, DAVID);
        assertThat(service.find(ACME).orElseThrow().origin()).isEqualTo(ExtensionService.Origin.BRAND);

        service.resetBrand(ACME);
        assertThat(service.find(ACME).orElseThrow().origin()).isEqualTo(ExtensionService.Origin.DEFAULT);
    }

    @Test
    void locking_without_a_decision_locks_the_manifest_s_default() {
        service.lockForBrand(ACME, true, DAVID);

        var status = service.find(ACME).orElseThrow();
        assertThat(status.enabled()).isTrue();
        assertThat(status.origin()).isEqualTo(ExtensionService.Origin.BRAND_LOCKED);
    }

    @Test
    void a_namespace_of_no_brand_cannot_decide_for_one_and_sees_no_brand_decisions() {
        brands.save(BrandActivation.of("erni", ACME, false, true, DAVID));
        brand = Optional.empty();

        assertThat(service.find(ACME).orElseThrow().origin()).isEqualTo(ExtensionService.Origin.DEFAULT);
        assertThat(service.currentBrand()).isEmpty();
        assertThatThrownBy(() -> service.decideForBrand(ACME, false, false, DAVID))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("no brand");
    }
}
