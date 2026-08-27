package ai.mindconnect.llm;

import ai.mindconnect.common.util.encryption.EncryptionHelper;
import ai.mindconnect.llm.domain.LlmConfig;
import ai.mindconnect.llm.domain.LlmProvider;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

class LlmConfigResolvedTest {

    private static LlmConfig config(String model, String baseUrl, String apiKey) {
        return new LlmConfig(UUID.randomUUID(), "test", LlmProvider.OPENAI,
                model, baseUrl, apiKey, 0.7, 4096, Map.of(), null, false, null, null, null, null);
    }

    // --- resolved() — env-var expansion only ---

    @Test
    void resolvedExpandsApiKey() {
        // We can't inject an env map into LlmConfig.resolved() directly, so we
        // verify the happy path using a var that is expected to be absent and
        // supplying a default.
        var cfg = config("gpt-4o", "https://api.openai.com", "${MY_TEST_API_KEY:plain-key}");
        assertThat(cfg.resolved().apiKey()).isEqualTo("plain-key");
    }

    @Test
    void resolvedExpandsBaseUrl() {
        var cfg = config("gpt-4o", "${LLM_BASE_URL:https://api.openai.com}", "key");
        assertThat(cfg.resolved().baseUrl()).isEqualTo("https://api.openai.com");
    }

    @Test
    void resolvedExpandsModel() {
        var cfg = config("${LLM_MODEL:gpt-4o-mini}", "https://api.openai.com", "key");
        assertThat(cfg.resolved().model()).isEqualTo("gpt-4o-mini");
    }

    @Test
    void resolvedLeavesNonPlaceholderValuesUnchanged() {
        var cfg = config("gpt-4o", "https://api.openai.com", "sk-plain");
        var r = cfg.resolved();
        assertThat(r.model()).isEqualTo("gpt-4o");
        assertThat(r.baseUrl()).isEqualTo("https://api.openai.com");
        assertThat(r.apiKey()).isEqualTo("sk-plain");
    }

    @Test
    void resolvedPreservesNonStringFields() {
        var cfg = config("gpt-4o", "https://api.openai.com", "key");
        var r = cfg.resolved();
        assertThat(r.id()).isEqualTo(cfg.id());
        assertThat(r.provider()).isEqualTo(LlmProvider.OPENAI);
        assertThat(r.defaultTemperature()).isEqualTo(0.7);
        assertThat(r.maxOutputTokens()).isEqualTo(4096);
    }

    @Test
    void resolvedThrowsWhenEnvVarAbsentAndNoDefault() {
        var cfg = config("gpt-4o", "https://api.openai.com", "${DEFINITELY_NOT_SET_XYZ}");
        assertThatThrownBy(cfg::resolved)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DEFINITELY_NOT_SET_XYZ");
    }

    // --- resolved(EncryptionHelper) — env expansion + decryption ---

    @Test
    void resolvedWithEncryptionDecryptsEncPrefix() throws Exception {
        var encryption = new EncryptionHelper("0123456789abcdef"); // 16-byte AES key
        String encrypted = EncryptionHelper.ENC + encryption.encrypt("real-api-key");

        var cfg = config("gpt-4o", "https://api.openai.com", encrypted);
        assertThat(cfg.resolved(encryption).apiKey()).isEqualTo("real-api-key");
    }

    @Test
    void resolvedWithEncryptionHandlesPlainPrefix() {
        var encryption = new EncryptionHelper("0123456789abcdef");
        var cfg = config("gpt-4o", "https://api.openai.com", "plain:my-key");
        assertThat(cfg.resolved(encryption).apiKey()).isEqualTo("my-key");
    }

    @Test
    void resolvedWithEncryptionHandlesBareKey() {
        var encryption = new EncryptionHelper("0123456789abcdef");
        var cfg = config("gpt-4o", "https://api.openai.com", "sk-bare");
        assertThat(cfg.resolved(encryption).apiKey()).isEqualTo("sk-bare");
    }

    @Test
    void resolvedWithEncryptionExpandsEnvVarThenDecrypts() throws Exception {
        // Simulates: apiKey stored as "${MY_KEY}" in JSON, env var holds enc:… value.
        // We can't inject env here, so we use the default fallback to carry the enc: value.
        var encryption = new EncryptionHelper("0123456789abcdef");
        String encrypted = EncryptionHelper.ENC + encryption.encrypt("secret");

        // Store the encrypted value as a default fallback so resolved() picks it up
        var cfg = config("gpt-4o", "https://api.openai.com", "${ABSENT_VAR:" + encrypted + "}");
        assertThat(cfg.resolved(encryption).apiKey()).isEqualTo("secret");
    }

    @Test
    void resolvedWithEncryptionAlsoExpandsBaseUrl() throws Exception {
        var encryption = new EncryptionHelper("0123456789abcdef");
        var cfg = config("gpt-4o", "${MY_BASE_URL:https://custom.api.com}", "key");
        assertThat(cfg.resolved(encryption).baseUrl()).isEqualTo("https://custom.api.com");
    }
}
