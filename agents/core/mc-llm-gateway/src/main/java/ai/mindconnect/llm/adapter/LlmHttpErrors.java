package ai.mindconnect.llm.adapter;

import ai.mindconnect.llm.domain.LlmConfig;
import ai.mindconnect.llm.domain.LlmConfigType;
import ai.mindconnect.llm.domain.LlmTransientException;
import org.slf4j.Logger;

/**
 * One place where every provider adapter turns a failed HTTP response into a
 * log line and an exception message.
 *
 * <p>It exists for the rate limit. A bare {@code HTTP 429} in the log says
 * nothing about <em>which</em> of a dozen configured models hit its limit, how
 * long the provider wants us to wait, or whether anything is going to be done
 * about it — so every 429/529 is logged with the config name, the model, the
 * {@code Retry-After} hint, and what the config's own {@code retry} policy and
 * {@link LlmConfig#fallbackModels() fallback models} will do next. That last
 * part is what turns "it was slow" into "this model is rate-limited and has no
 * fallback configured".
 *
 * <p>The provider's error body is kept — that is where the useful detail lives
 * (which quota, per minute or per day) — but flattened to one line and
 * truncated, so a rate limit costs one readable log entry, not a page.
 */
public final class LlmHttpErrors {

    /** How much of the provider's error body reaches the log. */
    static final int MAX_BODY_CHARS = 500;

    private LlmHttpErrors() {
    }

    /**
     * Logs a non-2xx provider response. Rate limits (429) and overload (529)
     * get the dedicated, greppable {@code LLM RATE LIMIT} /
     * {@code LLM OVERLOADED} form with the config, the wait hint and what
     * happens next; anything else keeps the plain "call failed" shape.
     *
     * @param provider          the adapter's provider label, e.g. {@code "Anthropic"}
     * @param retryAfterHeader  the response's {@code Retry-After} header, or {@code null}
     */
    public static void logHttpError(Logger log, String provider, LlmConfig config,
                                    int status, String retryAfterHeader, String body) {
        if (!LlmTransientException.isTransient(status)) {
            log.warn("LLM call failed: HTTP {} from {} — config '{}', model '{}' — body: {}",
                    status, provider, name(config), model(config), compact(body));
            return;
        }
        log.warn("{} from {} — config '{}', model '{}'{}; {} — provider said: {}",
                status == 429 ? "LLM RATE LIMIT (HTTP 429)" : "LLM OVERLOADED (HTTP " + status + ")",
                provider, name(config), model(config),
                retryAfterHint(LlmTransientException.parseRetryAfterMillis(retryAfterHeader)),
                whatHappensNext(config), compact(body));
    }

    /**
     * The message a {@link LlmTransientException} carries: the same facts as
     * the log line, minus the provider body, so the error that reaches a user
     * or a trace names the model that was limited rather than a bare status.
     */
    public static String transientMessage(String provider, LlmConfig config, int status,
                                          long retryAfterMillis) {
        String what = status == 429 ? "rate limit" : "overloaded";
        return provider + " " + what + " (HTTP " + status + ") for config '" + name(config)
                + "' (model " + model(config) + ")" + retryAfterHint(retryAfterMillis);
    }

    /** {@code ", Retry-After 20 s"}, or a note that the provider gave no hint. */
    private static String retryAfterHint(long retryAfterMillis) {
        if (retryAfterMillis <= 0) return ", no Retry-After hint";
        return ", Retry-After " + (retryAfterMillis >= 1000
                ? (retryAfterMillis / 1000) + " s"
                : retryAfterMillis + " ms");
    }

    /**
     * What the configuration says should happen to this call: retry with
     * backoff, switch to a fallback model, or nothing at all — the last being
     * the case worth seeing in a log, because it is the one an admin can fix.
     */
    private static String whatHappensNext(LlmConfig config) {
        if (config == null) return "no retry or fallback configured";
        if (config.type() != LlmConfigType.CHAT) {
            // Retry and fallback are chat-path decorators; saying "off" here
            // would read as a misconfiguration rather than as what it is.
            return "the call fails — retry and fallback apply to chat configs only";
        }
        StringBuilder next = new StringBuilder();
        var retry = config.retry();
        if (retry != null && retry.enabled()) {
            next.append("retrying up to ").append(retry.maxAttempts()).append(" attempt(s)");
        } else {
            next.append("retry is off for this config");
        }
        if (config.hasFallbackModels()) {
            next.append(", then falling back to ").append(String.join(" → ", config.fallbackModels()));
        } else {
            next.append(", no fallback models configured");
        }
        return next.toString();
    }

    /** The provider's body on one line, bounded — enough to see which quota was hit. */
    static String compact(String body) {
        if (body == null || body.isBlank()) return "(no body)";
        String oneLine = body.replaceAll("\\s+", " ").trim();
        return oneLine.length() <= MAX_BODY_CHARS
                ? oneLine
                : oneLine.substring(0, MAX_BODY_CHARS) + "… (" + oneLine.length() + " chars)";
    }

    private static String name(LlmConfig config) {
        return config == null || config.name() == null ? "?" : config.name();
    }

    private static String model(LlmConfig config) {
        return config == null || config.model() == null ? "?" : config.model();
    }
}
