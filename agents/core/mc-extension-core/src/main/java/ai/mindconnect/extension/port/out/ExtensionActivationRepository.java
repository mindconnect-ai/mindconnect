package ai.mindconnect.extension.port.out;

import ai.mindconnect.extension.domain.ExtensionActivation;
import ai.mindconnect.extension.domain.ExtensionId;

import java.util.List;
import java.util.Optional;

/**
 * The decisions a namespace's admins took about extensions. Deliberately not
 * a list of extensions: what exists comes from the classpath, this records
 * only what should differ from the manifests' defaults. An empty repository
 * is the shipped state.
 *
 * <p>Like every store, an implementation is bound to the one namespace it
 * serves; above it a routing proxy picks the adapter for the namespace the
 * request works in.
 */
public interface ExtensionActivationRepository {

    Optional<ExtensionActivation> find(ExtensionId id);

    /** Every decision taken, in no particular order. */
    List<ExtensionActivation> all();

    /** Records a decision, replacing an earlier one for the same extension. */
    void save(ExtensionActivation activation);

    /** Forgets the decision — the extension is back to what its manifest says. */
    void delete(ExtensionId id);
}
