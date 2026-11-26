package ai.mindconnect.llm;

import ai.mindconnect.llm.management.api.domain.LlmConfigId;
import ai.mindconnect.common.util.encryption.EncryptionHelper;
import ai.mindconnect.llm.adapter.env.EncryptingLlmConfigRepository;
import ai.mindconnect.llm.management.api.domain.LlmConfig;
import ai.mindconnect.llm.management.api.port.out.LlmConfigRepository;
import org.junit.jupiter.api.Test;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class EncryptingLlmConfigRepositoryTest {

    /** Captures whatever the encrypting decorator hands down to storage. */
    private static final class CapturingRepo implements LlmConfigRepository {
        LlmConfig saved;
        @Override public void save(LlmConfig config) { this.saved = config; }
        @Override public Optional<LlmConfig> findById(LlmConfigId id) { return Optional.ofNullable(saved); }
        @Override public Optional<LlmConfig> findByName(String name) { return Optional.ofNullable(saved); }
        @Override public List<LlmConfig> findAll() { return saved == null ? List.of() : List.of(saved); }
        @Override public void deleteById(LlmConfigId id) {}
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
    void a_key_of_the_earlier_scheme_is_stored_in_the_current_one() throws Exception {
        Cipher ecb = Cipher.getInstance("AES/ECB/PKCS5Padding");
        ecb.init(Cipher.ENCRYPT_MODE, new SecretKeySpec("0123456789abcdef".getBytes(StandardCharsets.UTF_8), "AES"));
        String earlier = EncryptionHelper.ENC
                + Base64.getEncoder().encodeToString(ecb.doFinal("sk-plain-secret".getBytes(StandardCharsets.UTF_8)));
        var delegate = new CapturingRepo();

        repo(delegate).save(withKey(earlier));
        String rewritten = delegate.saved.apiKey();
        repo(delegate).save(withKey(rewritten));

        assertThat(rewritten).startsWith("enc:v2:");
        assertThat(encryption.resolve(rewritten)).isEqualTo("sk-plain-secret");
        assertThat(delegate.saved.apiKey()).isEqualTo(rewritten);
    }

    @Test
    void plainPrefixedKeyIsStoredVerbatim() {
        var delegate = new CapturingRepo();
        repo(delegate).save(withKey(EncryptionHelper.PLAIN + "my-key"));
        assertThat(delegate.saved.apiKey()).isEqualTo(EncryptionHelper.PLAIN + "my-key");
    }
}
