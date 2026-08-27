package ai.mindconnect.llm.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Per-config concurrency limit for LLM requests, consumed by
 * {@code ai.mindconnect.llm.service.ThrottlingLlmGateway}. Attached to an
 * {@link LlmConfig} so different models/providers can be throttled
 * independently.
 *
 * <p>A {@code null} {@code RateLimitConfig} on the config means <em>no
 * throttling</em>. When present, at most {@link #maxConcurrentRequests}
 * requests for that config run at once (process-wide, across all turns,
 * sub-agents, and tool loops); excess callers block until a slot frees up.
 *
 * <p>Primary use: keep {@code run_agents} fan-out under a provider's
 * concurrency / rate limit.
 *
 * @param maxConcurrentRequests max in-flight requests for this config; min 1
 */
public record RateLimitConfig(
        int maxConcurrentRequests
) {
    public static final int DEFAULT_MAX_CONCURRENT_REQUESTS = 4;

    /** Normalises out-of-range values so a malformed config can't disable the limit. */
    public RateLimitConfig {
        if (maxConcurrentRequests < 1) maxConcurrentRequests = 1;
    }

    @JsonCreator
    public static RateLimitConfig fromJson(
            @JsonProperty("maxConcurrentRequests") int maxConcurrentRequests) {
        return new RateLimitConfig(maxConcurrentRequests);
    }

    public static RateLimitConfig of(int maxConcurrentRequests) {
        return new RateLimitConfig(maxConcurrentRequests);
    }
}
