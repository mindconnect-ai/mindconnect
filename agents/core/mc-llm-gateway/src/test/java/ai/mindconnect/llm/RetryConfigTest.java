package ai.mindconnect.llm;

import ai.mindconnect.llm.domain.LlmConfig;
import ai.mindconnect.llm.domain.RetryConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RetryConfigTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void deserialisesRetryBlockFromConfigJson() throws Exception {
        String json = """
            {
              "name": "claude-haiku-default",
              "provider": "ANTHROPIC",
              "model": "claude-haiku-4-5",
              "retry": { "enabled": true, "maxAttempts": 5,
                         "baseBackoffMillis": 2000, "maxBackoffMillis": 30000 }
            }
            """;
        LlmConfig config = mapper.readValue(json, LlmConfig.class);

        assertThat(config.retry()).isNotNull();
        assertThat(config.retry().maxAttempts()).isEqualTo(5);
        assertThat(config.retry().baseBackoffMillis()).isEqualTo(2000);
        assertThat(config.retry().maxBackoffMillis()).isEqualTo(30000);
    }

    @Test
    void configWithoutRetryFieldHasNoPolicy() throws Exception {
        // Old persisted configs have no "retry" key — must still load, and a
        // missing policy means NO retry (null), not silent defaults.
        String json = """
            { "name": "old", "provider": "OPENAI", "model": "gpt-4o" }
            """;
        LlmConfig config = mapper.readValue(json, LlmConfig.class);

        assertThat(config.retry()).isNull();
    }

    @Test
    void partialRetryBlockDefaultsMissingFields() {
        RetryConfig r = RetryConfig.fromJson(null, 3, null, null);
        assertThat(r.enabled()).isTrue();
        assertThat(r.maxAttempts()).isEqualTo(3);
        assertThat(r.baseBackoffMillis()).isEqualTo(RetryConfig.DEFAULT_BASE_BACKOFF_MILLIS);
        assertThat(r.maxBackoffMillis()).isEqualTo(RetryConfig.DEFAULT_MAX_BACKOFF_MILLIS);
    }

    @Test
    void backoffIsExponentialAndCapped() {
        RetryConfig r = new RetryConfig(true, 6, 1000, 5000);
        assertThat(r.backoffForAttempt(1, 0)).isEqualTo(1000);  // 1000 * 2^0
        assertThat(r.backoffForAttempt(2, 0)).isEqualTo(2000);  // 1000 * 2^1
        assertThat(r.backoffForAttempt(3, 0)).isEqualTo(4000);  // 1000 * 2^2
        assertThat(r.backoffForAttempt(4, 0)).isEqualTo(5000);  // capped at maxBackoff
        assertThat(r.backoffForAttempt(10, 0)).isEqualTo(5000); // still capped
    }

    @Test
    void serverRetryAfterIsHonouredInFull_notCappedAtMaxBackoff() {
        RetryConfig r = new RetryConfig(true, 4, 1000, 5000);
        // Server hint wins over the computed exponential value...
        assertThat(r.backoffForAttempt(1, 3_000)).isEqualTo(3_000);
        // ...and is honoured IN FULL even when it exceeds maxBackoffMillis,
        // because the server knows exactly when the limit clears (e.g. the
        // per-minute token window reset). Clamping to maxBackoff would retry
        // too early and hit the same 429 again.
        assertThat(r.backoffForAttempt(1, 60_000)).isEqualTo(60_000);
        // Only the generous safety ceiling bounds an absurd header value.
        assertThat(r.backoffForAttempt(1, 999_999_999L))
                .isEqualTo(RetryConfig.RETRY_AFTER_CEILING_MILLIS);
    }

    @Test
    void invalidValuesAreNormalised() {
        RetryConfig r = new RetryConfig(true, 0, -5, -1);
        assertThat(r.maxAttempts()).isEqualTo(1);       // floored to 1
        assertThat(r.baseBackoffMillis()).isEqualTo(0); // floored to 0
        assertThat(r.maxBackoffMillis()).isEqualTo(0);  // raised to baseBackoff
    }
}
