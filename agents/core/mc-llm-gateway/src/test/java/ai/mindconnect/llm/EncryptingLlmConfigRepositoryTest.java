package ai.mindconnect.llm;

import ai.mindconnect.common.util.encryption.EncryptionHelper;
import ai.mindconnect.llm.adapter.file.EncryptingLlmConfigRepository;
import ai.mindconnect.llm.domain.LlmConfig;
import ai.mindconnect.llm.port.out.LlmConfigRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class EncryptingLlmConfigRepositoryTest {

    /** Captures whatever the encrypting decorator hands down to storage. */
    private static final class CapturingRepo implements LlmConfigRepository {
        LlmConfig saved;
        @Override public void save(LlmConfig config) { this.saved = config; }
        @Override public Optional<LlmConfig> findById(UUID id) { return Optional.ofNullable(saved); }
        @Override public Optional<LlmConfig> findByName(String name) { return Optional.ofNullable(saved); }
        @Override public List<LlmConfig> findAll() { return saved == null ? List.of() : List.of(saved); }
        @Override public void deleteById(UUID id) {}
    }

    private final EncryptionHelper encryption = new EncryptionHelper("0123456789abcdef");

    private EncryptingLlmConfigRepository repo(CapturingRepo delegate) {
        return new EncryptingLlmConfigRepository(delegate, encryption);
    }

    private static LlmConfig withKey(String apiKey) {
        return LlmConfig.claude("test", "claude-sonnet-4-6", apiKey);
    }

    @Test
    void plainKeyIsEncrypted() {
        var delegate = new CapturingRepo();
        repo(delegate).save(withKey("sk-plain-secret"));
        assertThat(delegate.saved.apiKey()).startsWith(EncryptionHelper.ENC);
    }

    @Test
    void envPlaceholderIsStoredVerbatim() {
        var delegate = new CapturingRepo();
        repo(delegate).save(withKey("${OPENAI_API_KEY}"));
        assertThat(delegate.saved.apiKey()).isEqualTo("${OPENAI_API_KEY}");
    }

    @Test
    void envPlaceholderWithDefaultIsStoredVerbatim() {
        var delegate = new CapturingRepo();
        repo(delegate).save(withKey("${OPENAI_API_KEY:sk-fallback}"));
        assertThat(delegate.saved.apiKey()).isEqualTo("${OPENAI_API_KEY:sk-fallback}");
    }

    @Test
    void alreadyEncryptedKeyIsNotDoubleEncrypted() {
        var delegate = new CapturingRepo();
        String already = EncryptionHelper.ENC + "somebase64==";
        repo(delegate).save(withKey(already));
        assertThat(delegate.saved.apiKey()).isEqualTo(already);
    }

    @Test
    void plainPrefixedKeyIsStoredVerbatim() {
        var delegate = new CapturingRepo();
        repo(delegate).save(withKey(EncryptionHelper.PLAIN + "my-key"));
        assertThat(delegate.saved.apiKey()).isEqualTo(EncryptionHelper.PLAIN + "my-key");
    }
}
