package ai.mindconnect.llm.domain;

import java.util.HashMap;
import java.util.Map;

/**
 * Resolves provider-specific parameters for a call by merging the per-request
 * map over the per-config map. Request keys win; either side may be null/empty.
 * <p>
 * This is the generic channel through which reasoning controls (Anthropic
 * {@code thinking}/{@code effort}, OpenAI {@code reasoning_effort}, Gemini
 * {@code thinkingConfig}) flow to the adapters. Each adapter reads only the keys
 * it understands and ignores the rest.
 */
public final class LlmParams {

    private LlmParams() {}

    /** Returns config.additionalParams with request.additionalParams overlaid. */
    public static Map<String, Object> merge(LlmConfig config, LlmRequest request) {
        Map<String, Object> merged = new HashMap<>();
        if (config != null && config.additionalParams() != null) {
            merged.putAll(config.additionalParams());
        }
        if (request != null && request.additionalParams() != null) {
            merged.putAll(request.additionalParams());
        }
        return merged;
    }

    /** Reads a string param, or {@code null} when absent/blank. */
    public static String string(Map<String, Object> params, String key) {
        Object v = params.get(key);
        if (v == null) return null;
        String s = v.toString().trim();
        return s.isEmpty() ? null : s;
    }
}
