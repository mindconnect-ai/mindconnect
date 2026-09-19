package ai.mindconnect.credentials.port.out;

import ai.mindconnect.credentials.domain.OAuthProvider;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The OAuth apps this installation can connect through — what the operator
 * registered, or what a module brought along. Installation-wide: a client
 * registration is the installation's, not a user's and not a namespace's.
 */
public interface OAuthProviderRepository {

    void save(OAuthProvider provider);

    Optional<OAuthProvider> findById(UUID id);

    /** By the stable lookup key a {@code ConnectionSpec} names. */
    Optional<OAuthProvider> findByName(String name);

    List<OAuthProvider> findAll();

    void deleteById(UUID id);
}
