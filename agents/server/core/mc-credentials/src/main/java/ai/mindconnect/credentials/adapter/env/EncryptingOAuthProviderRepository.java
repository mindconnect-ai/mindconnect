package ai.mindconnect.credentials.adapter.env;

import ai.mindconnect.common.util.encryption.EncryptionHelper;
import ai.mindconnect.credentials.domain.OAuthProvider;
import ai.mindconnect.credentials.port.out.OAuthProviderRepository;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Keeps an app registration's {@code clientSecret} {@code enc:} at rest, the
 * way every other stored secret is.
 *
 * <p>Only that one field. Client id, endpoints and scopes are not secrets —
 * a client id travels in every authorization URL — and encrypting them would
 * make a registration unreadable for no gain.
 *
 * <p>A public client has no secret at all, which is the usual case for an app
 * registration several installations share; then this does nothing.
 */
public class EncryptingOAuthProviderRepository implements OAuthProviderRepository {

    private final OAuthProviderRepository delegate;
    private final EncryptionHelper encryption;

    public EncryptingOAuthProviderRepository(OAuthProviderRepository delegate, EncryptionHelper encryption) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.encryption = Objects.requireNonNull(encryption, "encryption");
    }

    @Override
    public void save(OAuthProvider provider) {
        delegate.save(provider.clientSecret() == null || provider.clientSecret().isBlank()
                ? provider
                : provider.withClientSecret(encryption.encryptTagged(provider.clientSecret())));
    }

    @Override
    public Optional<OAuthProvider> findById(UUID id) {
        return delegate.findById(id).map(this::decrypted);
    }

    @Override
    public Optional<OAuthProvider> findByName(String name) {
        return delegate.findByName(name).map(this::decrypted);
    }

    @Override
    public List<OAuthProvider> findAll() {
        return delegate.findAll().stream().map(this::decrypted).toList();
    }

    @Override
    public void deleteById(UUID id) {
        delegate.deleteById(id);
    }

    private OAuthProvider decrypted(OAuthProvider provider) {
        return provider.clientSecret() == null
                ? provider
                : provider.withClientSecret(encryption.resolve(provider.clientSecret()));
    }
}
