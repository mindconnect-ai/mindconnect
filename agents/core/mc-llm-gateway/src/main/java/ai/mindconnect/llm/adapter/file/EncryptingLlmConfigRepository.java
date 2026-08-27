package ai.mindconnect.llm.adapter.file;

import ai.mindconnect.common.util.EnvVarResolver;
import ai.mindconnect.common.util.encryption.EncryptionHelper;
import ai.mindconnect.llm.domain.LlmConfig;
import ai.mindconnect.llm.port.out.LlmConfigRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Decorator that encrypts the {@code apiKey} with an {@code enc:} prefix before
 * delegating to the underlying repository. Reads pass through unchanged —
 * decryption happens at the gateway level via {@link EncryptionHelper#resolve}.
 * <p>
 * Keys already carrying an {@code enc:} or {@code plain:} prefix are stored
 * as-is so re-saving an already-encrypted config is idempotent.
 */
public class EncryptingLlmConfigRepository implements LlmConfigRepository {

    private final LlmConfigRepository delegate;
    private final EncryptionHelper encryption;

    public EncryptingLlmConfigRepository(LlmConfigRepository delegate, EncryptionHelper encryption) {
        this.delegate = delegate;
        this.encryption = encryption;
    }

    @Override
    public void save(LlmConfig config) {
        delegate.save(config.withApiKey(encryptKey(config.apiKey())));
    }

    @Override
    public Optional<LlmConfig> findById(UUID id) {
        return delegate.findById(id);
    }

    @Override
    public Optional<LlmConfig> findByName(String name) {
        return delegate.findByName(name);
    }

    @Override
    public List<LlmConfig> findAll() {
        return delegate.findAll();
    }

    @Override
    public void deleteById(UUID id) {
        delegate.deleteById(id);
    }

    private String encryptKey(String apiKey) {
        if (apiKey == null) return null;
        if (apiKey.startsWith(EncryptionHelper.ENC) || apiKey.startsWith(EncryptionHelper.PLAIN)) {
            return apiKey; // already tagged — don't double-encrypt
        }
        if (EnvVarResolver.containsPlaceholder(apiKey)) {
            // ${VAR} reference — store verbatim. It is resolved at use time
            // (LlmConfig.resolved()) and the resolved value may itself carry an
            // enc:/plain: tag. Encrypting it here would destroy the placeholder.
            return apiKey;
        }
        try {
            return EncryptionHelper.ENC + encryption.encrypt(apiKey);
        } catch (Exception e) {
            throw new RuntimeException("Failed to encrypt API key", e);
        }
    }
}
