package ai.mindconnect.llm.domain;

import java.util.HashMap;
import java.util.Map;

/**
 * The provider-specific parameters of one call: the request's values laid
 * over the config's, so a request can override what the config sets. This is
 * the channel the reasoning controls travel through — Anthropic's
 * {@code thinking} and {@code effort}, OpenAI's {@code reasoning_effort} — and
 * each adapter reads only the keys it understands.
 */
public final class LlmParameterMap {

    private final Map<String, Object> values;

    private LlmParameterMap(Map<String, Object> values) {
        this.values = values;
    }

    /** @param request {@code null} for a call without a request of its own, such as a transcription */
    public static LlmParameterMap of(LlmConfig config, LlmRequest request) {
        Map<String, Object> merged = new HashMap<>(config.additionalParams());
        if (request != null) merged.putAll(request.additionalParams());
        return new LlmParameterMap(merged);
    }

    /** The value under {@code key} as text, trimmed, or {@code null} when it is absent or blank. */
    public String text(String key) {
        Object value = values.get(key);
        if (value == null) return null;
        String text = value.toString().trim();
        return text.isEmpty() ? null : text;
    }
}
