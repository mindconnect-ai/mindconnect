package ai.mindconnect.credentials.adapter.env;

import ai.mindconnect.agent.UserId;
import ai.mindconnect.common.util.encryption.EncryptionHelper;
import ai.mindconnect.credentials.domain.ApiKeyCreds;
import ai.mindconnect.credentials.domain.Connection;
import ai.mindconnect.credentials.domain.ConnectionId;
import ai.mindconnect.credentials.domain.FormCreds;
import ai.mindconnect.credentials.domain.OAuth2UserCreds;
import ai.mindconnect.credentials.domain.UserCredentials;
import ai.mindconnect.credentials.port.out.ConnectionRepository;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Decorator that keeps a connection's {@linkplain Connection#credentials()
 * credentials} encrypted at rest — {@code enc:} prefixed, the way a user's own
 * variables and the LLM credentials are — and plain in memory, so nothing
 * above this ever sees a ciphertext.
 *
 * <p>Only the credentials. {@link Connection#settings()} is host, port and
 * folder: readable on purpose, because a user correcting a typo has to see
 * what is there.
 *
 * <p>A value that no longer decrypts — a rotated key — is left out with a
 * warning rather than taking the whole connection down; the user sees a
 * connection that needs its password again, not an installation that will not
 * start.
 */
public class EncryptingConnectionRepository implements ConnectionRepository {

    private final ConnectionRepository delegate;
    private final EncryptionHelper encryption;

    public EncryptingConnectionRepository(ConnectionRepository delegate, EncryptionHelper encryption) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.encryption = Objects.requireNonNull(encryption, "encryption");
    }

    @Override
    public void save(Connection connection) {
        delegate.save(connection.withCredentials(encrypted(connection.credentials()), connection.updatedAt()));
    }

    @Override
    public Optional<Connection> findById(ConnectionId id) {
        return delegate.findById(id).map(this::decrypted);
    }

    @Override
    public List<Connection> findByUser(UserId userId) {
        return delegate.findByUser(userId).stream().map(this::decrypted).toList();
    }

    @Override
    public void deleteById(ConnectionId id) {
        delegate.deleteById(id);
    }

    private UserCredentials encrypted(UserCredentials credentials) {
        return map(credentials, encryption::encryptValues, encryption::encryptTagged);
    }

    private Connection decrypted(Connection connection) {
        UserCredentials plain = map(connection.credentials(),
                stored -> encryption.decryptValues(stored, "connection '" + connection.key() + "'"),
                encryption::resolve);
        return plain == connection.credentials() ? connection : connection.withCredentials(plain, connection.updatedAt());
    }

    /**
     * One walk over the three shapes a credential can have. OAuth2 metadata
     * and scopes stay plain — they are not secrets, and an unreadable scope
     * list would make a working token look broken.
     */
    private static UserCredentials map(UserCredentials credentials,
                                       java.util.function.UnaryOperator<Map<String, String>> values,
                                       java.util.function.UnaryOperator<String> single) {
        if (credentials == null) return null;
        return switch (credentials) {
            case FormCreds form -> form.mapValues(values);
            case ApiKeyCreds key -> new ApiKeyCreds(single.apply(key.value()));
            case OAuth2UserCreds oauth -> new OAuth2UserCreds(
                    single.apply(oauth.accessToken()),
                    oauth.refreshToken() == null ? null : single.apply(oauth.refreshToken()),
                    oauth.expiresAt(), oauth.scopes(), oauth.metadata());
        };
    }
}
