package ai.mindconnect.llm.domain;

/**
 * Thrown by an {@link ai.mindconnect.llm.port.out.LlmGateway} when the provider
 * rejects a request with a <em>transient</em>, retryable error — typically
 * HTTP 429 (rate limit) or 529 (overloaded). These occur <em>before</em> any
 * stream content is produced, so the call can be safely retried.
 *
 * <p>The generic retry decorator
 * ({@link ai.mindconnect.llm.service.RetryingLlmGateway}) catches this and
 * applies backoff; non-transient failures should remain plain
 * {@link RuntimeException}s so they propagate immediately.
 *
 * <p>{@code status} carries the provider HTTP status (e.g. 429, 529; 0 if
 * unknown); {@code retryAfterMillis} the server-suggested wait from a
 * {@code Retry-After} header, in millis, or 0 when the server gave no hint.
 */
public class LlmTransientException extends RuntimeException {

    private final int status;
    private final long retryAfterMillis;

    public LlmTransientException(int status, long retryAfterMillis, String message) {
        super(message);
        this.status = status;
        this.retryAfterMillis = retryAfterMillis;
    }

    public int status() {
        return status;
    }

    /** Server-suggested backoff in millis, or 0 if none was provided. */
    public long retryAfterMillis() {
        return retryAfterMillis;
    }

}
