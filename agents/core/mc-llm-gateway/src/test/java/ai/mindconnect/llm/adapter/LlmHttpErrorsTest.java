package ai.mindconnect.llm.adapter;

import ai.mindconnect.llm.domain.LlmConfig;
import ai.mindconnect.llm.domain.RetryConfig;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** What a 429 says once it reaches the log or an error message. */
class LlmHttpErrorsTest {

    private static final LlmConfig CLAUDE = LlmConfig.claude("claude-default", "claude-sonnet-4-6", "key");

    @Test
    void aRateLimitMessageNamesTheConfigTheModelAndTheWait() {
        String message = LlmHttpErrors.transientMessage("Anthropic", CLAUDE, 429, 20_000);

        assertThat(message)
                .contains("rate limit (HTTP 429)")
                .contains("claude-default")
                .contains("claude-sonnet-4-6")
                .contains("Retry-After 20 s");
    }

    @Test
    void aMissingRetryAfterIsSaidSoRatherThanShownAsZero() {
        assertThat(LlmHttpErrors.transientMessage("Anthropic", CLAUDE, 429, 0))
                .contains("no Retry-After hint")
                .doesNotContain("0 ms");
    }

    @Test
    void anOverloadedProviderIsNotCalledARateLimit() {
        assertThat(LlmHttpErrors.transientMessage("Anthropic", CLAUDE, 529, 0))
                .contains("overloaded (HTTP 529)");
    }

    @Test
    void theProviderBodyIsFlattenedToOneLineAndBounded() {
        assertThat(LlmHttpErrors.compact("{\n  \"error\": \"rate_limit\"\n}"))
                .isEqualTo("{ \"error\": \"rate_limit\" }");
        assertThat(LlmHttpErrors.compact(null)).isEqualTo("(no body)");

        String long_ = "x".repeat(LlmHttpErrors.MAX_BODY_CHARS + 50);
        assertThat(LlmHttpErrors.compact(long_))
                .hasSizeLessThan(long_.length())
                .endsWith("chars)");
    }

    @Test
    void aConfigWithFallbacksSaysWhereTheCallGoesNext() {
        LlmConfig config = CLAUDE
                .withFallbackModels(List.of("gpt-default", "gemini-default"));
        RecordingLogger log = new RecordingLogger();

        LlmHttpErrors.logHttpError(log, "Anthropic", config, 429, "10", "{\"error\":\"slow down\"}");

        assertThat(log.rendered())
                .contains("LLM RATE LIMIT (HTTP 429)")
                .contains("retry is off for this config")
                .contains("falling back to gpt-default → gemini-default")
                .contains("slow down");
    }

    @Test
    void aConfigWithoutFallbacksSaysThatTooBecauseThatIsTheFixableCase() {
        RecordingLogger log = new RecordingLogger();

        LlmHttpErrors.logHttpError(log, "Anthropic",
                CLAUDE.withRetry(RetryConfig.defaults()), 429, null, "");

        assertThat(log.rendered())
                .contains("retrying up to 4 attempt(s)")
                .contains("no fallback models configured")
                .contains("(no body)");
    }

    /** A minimal slf4j logger that keeps the one line it was given. */
    private static final class RecordingLogger extends org.slf4j.helpers.LegacyAbstractLogger {

        private String message;
        private Object[] arguments;

        String rendered() {
            return org.slf4j.helpers.MessageFormatter.arrayFormat(message, arguments).getMessage();
        }

        @Override protected String getFullyQualifiedCallerName() { return null; }

        @Override
        protected void handleNormalizedLoggingCall(org.slf4j.event.Level level,
                                                   org.slf4j.Marker marker, String messagePattern,
                                                   Object[] arguments, Throwable throwable) {
            this.message = messagePattern;
            this.arguments = arguments;
        }

        @Override public boolean isTraceEnabled() { return false; }
        @Override public boolean isDebugEnabled() { return false; }
        @Override public boolean isInfoEnabled() { return true; }
        @Override public boolean isWarnEnabled() { return true; }
        @Override public boolean isErrorEnabled() { return true; }
    }
}
