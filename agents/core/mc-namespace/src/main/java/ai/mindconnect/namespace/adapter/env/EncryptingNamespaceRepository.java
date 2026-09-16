package ai.mindconnect.namespace.adapter.env;

import ai.mindconnect.agent.Namespace;
import ai.mindconnect.agent.UserId;
import ai.mindconnect.common.util.encryption.EncryptionHelper;
import ai.mindconnect.namespace.domain.NamespaceDefinition;
import ai.mindconnect.namespace.port.out.NamespaceRepository;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Decorator that keeps a namespace's {@link NamespaceDefinition#environment()}
 * encrypted at rest — {@code enc:} prefixed, the way LLM credentials are —
 * and plain in memory: every value is encrypted on the way in and decrypted
 * on the way out. A stored value that no longer decrypts (a rotated key) is
 * left out with a warning rather than failing the whole record.
 */
public class EncryptingNamespaceRepository implements NamespaceRepository {

    private final NamespaceRepository delegate;
    private final EncryptionHelper encryption;

    public EncryptingNamespaceRepository(NamespaceRepository delegate, EncryptionHelper encryption) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.encryption = Objects.requireNonNull(encryption, "encryption");
    }

    @Override
    public Optional<NamespaceDefinition> findById(Namespace id) {
        return delegate.findById(id).map(this::decrypted);
    }

    @Override
    public List<NamespaceDefinition> findAll() {
        return delegate.findAll().stream().map(this::decrypted).toList();
    }

    @Override
    public List<NamespaceDefinition> findByMember(UserId user) {
        return delegate.findByMember(user).stream().map(this::decrypted).toList();
    }

    @Override
    public void save(NamespaceDefinition namespace) {
        delegate.save(encrypted(namespace));
    }

    @Override
    public boolean insert(NamespaceDefinition namespace) {
        return delegate.insert(encrypted(namespace));
    }

    @Override
    public boolean deleteById(Namespace id) {
        return delegate.deleteById(id);
    }

    /**
     * The variables encrypted for storage, plus any stored value that no longer decrypts:
     * it was left out when the record was read, so a save after renaming or inviting
     * would otherwise delete it for good.
     */
    private NamespaceDefinition encrypted(NamespaceDefinition namespace) {
        Map<String, String> stored = delegate.findById(namespace.id())
                .map(NamespaceDefinition::environment).orElse(Map.of());
        Map<String, String> environment = encryption.encryptValuesKeepingUnreadable(namespace.environment(), stored);
        return environment.isEmpty() ? namespace : namespace.withEnvironment(environment);
    }

    private NamespaceDefinition decrypted(NamespaceDefinition namespace) {
        if (namespace.environment().isEmpty()) return namespace;
        return namespace.withEnvironment(encryption.decryptValues(namespace.environment(), "namespace '" + namespace.id().value() + "'"));
    }
}
