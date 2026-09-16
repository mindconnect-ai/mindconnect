package ai.mindconnect.user.adapter.env;

import ai.mindconnect.agent.UserId;
import ai.mindconnect.common.util.encryption.EncryptionHelper;
import ai.mindconnect.user.domain.User;
import ai.mindconnect.user.port.out.UserRepository;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Decorator that keeps a user's {@link User#environment()} encrypted at rest
 * — {@code enc:} prefixed, the way LLM credentials are — and plain in memory:
 * every value is encrypted on the way in and decrypted on the way out, so the
 * rest of the application never sees a ciphertext and a user may store a
 * value that happens to begin with {@code enc:} or {@code plain:}. A stored
 * value that no longer decrypts (a rotated key) is left out with a warning
 * rather than failing the whole record.
 */
public class EncryptingUserRepository implements UserRepository {

    private final UserRepository delegate;
    private final EncryptionHelper encryption;

    public EncryptingUserRepository(UserRepository delegate, EncryptionHelper encryption) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.encryption = Objects.requireNonNull(encryption, "encryption");
    }

    @Override
    public Optional<User> findById(UserId id) {
        return delegate.findById(id).map(this::decrypted);
    }

    @Override
    public List<User> findAll() {
        return delegate.findAll().stream().map(this::decrypted).toList();
    }

    /**
     * Encrypts the variables on the way in, and keeps any stored value that no longer
     * decrypts: it was left out when the record was read, so without this the save
     * after an unrelated change — a login stamp — would delete it for good.
     */
    @Override
    public void save(User user) {
        Map<String, String> stored = delegate.findById(user.id()).map(User::environment).orElse(Map.of());
        Map<String, String> environment = encryption.encryptValuesKeepingUnreadable(user.environment(), stored);
        delegate.save(environment.isEmpty() ? user : user.withEnvironment(environment));
    }

    private User decrypted(User user) {
        if (user.environment().isEmpty()) return user;
        return user.withEnvironment(encryption.decryptValues(user.environment(), "user '" + user.id().value() + "'"));
    }
}
