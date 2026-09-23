package ai.mindconnect.extension.port.out;

import ai.mindconnect.extension.domain.BrandActivation;
import ai.mindconnect.extension.domain.ExtensionId;

import java.util.List;
import java.util.Optional;

/**
 * The decisions brand admins took about extensions, for the whole brand.
 * Installation-wide, keyed by brand — a brand is a set of namespaces, so
 * its decisions live beside them, not inside one. Only decisions are
 * stored; an empty repository is the shipped state.
 */
public interface BrandActivationRepository {

    Optional<BrandActivation> find(String brand, ExtensionId id);

    /** Every decision taken for the brand, in no particular order. */
    List<BrandActivation> all(String brand);

    /** Records a decision, replacing an earlier one for the same brand and extension. */
    void save(BrandActivation activation);

    /** Forgets the decision — the brand's namespaces are back to their own decisions and the manifests' defaults. */
    void delete(String brand, ExtensionId id);

    /** A repository that holds nothing and keeps nothing — for hosts without brands. */
    static BrandActivationRepository none() {
        return new BrandActivationRepository() {
            @Override public Optional<BrandActivation> find(String brand, ExtensionId id) { return Optional.empty(); }
            @Override public List<BrandActivation> all(String brand) { return List.of(); }
            @Override public void save(BrandActivation activation) {
                throw new UnsupportedOperationException("This host keeps no brand decisions");
            }
            @Override public void delete(String brand, ExtensionId id) { }
        };
    }
}
