package ai.mindconnect.llm.management.api.domain;


/**
 * Per-config retry policy for transient provider errors (HTTP 429 rate-limit /
 * 529 overloaded), followed by {@link ai.mindconnect.llm.service.RetryingLlmGateway}.
 * Attached to an {@link LlmConfig} so different models and providers can be
 * tuned independently; a config without one is not retried at all.
 *
 * <p>Backoff is exponential: attempt <i>n</i> waits
 * {@code min(maxBackoffMillis, baseBackoffMillis * 2^(n-1))}, unless the
 * provider supplied a {@code Retry-After} hint, which always wins.
 *
 * @param enabled           whether to retry at all; false → fail fast on the first transient error
 * @param maxAttempts       total attempts including the first (so 4 = 1 try + 3 retries); min 1
 * @param baseBackoffMillis backoff for the first retry; doubles each subsequent attempt
 * @param maxBackoffMillis  ceiling for a single computed backoff (does not cap a server Retry-After)
 */
public record RetryConfig(
        boolean enabled,
        int maxAttempts,
        long baseBackoffMillis,
        long maxBackoffMillis
) {
    public static final boolean DEFAULT_ENABLED = true;
    public static final int DEFAULT_MAX_ATTEMPTS = 4;
    public static final long DEFAULT_BASE_BACKOFF_MILLIS = 2_000L;
    public static final long DEFAULT_MAX_BACKOFF_MILLIS = 30_000L;

    /** Normalises out-of-range values so a malformed config can't disable backoff or loop forever. */
    public RetryConfig {
        if (maxAttempts < 1) maxAttempts = 1;
        if (baseBackoffMillis < 0) baseBackoffMillis = 0;
        if (maxBackoffMillis < baseBackoffMillis) maxBackoffMillis = baseBackoffMillis;
    }

    /**
     * The entry point of the stored form. Every field is optional so older
     * persisted configs (and configs that omit {@code retry} entirely) read
     * back with sensible defaults rather than failing.
     */
    public static RetryConfig fromJson(Boolean enabled, Integer maxAttempts, Long baseBackoffMillis,
                                       Long maxBackoffMillis) {
        return new RetryConfig(
                enabled != null ? enabled : DEFAULT_ENABLED,
                maxAttempts != null ? maxAttempts : DEFAULT_MAX_ATTEMPTS,
                baseBackoffMillis != null ? baseBackoffMillis : DEFAULT_BASE_BACKOFF_MILLIS,
                maxBackoffMillis != null ? maxBackoffMillis : DEFAULT_MAX_BACKOFF_MILLIS);
    }

    /** The most a {@code Retry-After} is honoured, so an absurd header cannot park a request for good. */
    public static final long RETRY_AFTER_CEILING_MILLIS = 300_000L;

    /**
     * Backoff in millis for the given 1-based attempt. A positive
     * {@code Retry-After} says how long the limit genuinely needs to clear,
     * so it is honoured in full up to {@link #RETRY_AFTER_CEILING_MILLIS};
     * clamping it to {@link #maxBackoffMillis} would retry too early and hit
     * the same limit again. Without a hint, the exponential schedule applies,
     * and that one is capped.
     */
    public long backoffForAttempt(int attempt, long retryAfterMillis) {
        if (retryAfterMillis > 0) return Math.min(retryAfterMillis, RETRY_AFTER_CEILING_MILLIS);
        long computed = baseBackoffMillis * (1L << Math.max(0, attempt - 1));
        return Math.min(computed, maxBackoffMillis);
    }
}
