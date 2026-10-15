package ai.mindconnect.llm.adapter.openai;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What OpenAI's model ids say about how a model must be called. A reasoning
 * model takes {@code max_completion_tokens} instead of {@code max_tokens}
 * and no {@code temperature}; from gpt-5.6 on, Chat Completions refuses
 * function tools together with reasoning. Both are read off the id, so a
 * snapshot or a fine-tune of a known line behaves like the line.
 */
public final class OpenAiModelPolicy {

    private static final Pattern GPT_VERSION =
            Pattern.compile("^gpt-(\\d+)(?:\\.(\\d+))?(?:-.*)?$");

    private OpenAiModelPolicy() {
    }

    /** The o-series and every GPT generation from 5 on reason; a gpt-6 must not be sent a temperature either. */
    public static boolean isReasoningModel(String modelId) {
        if (modelId == null) return false;
        String lower = modelId.toLowerCase();
        if (lower.startsWith("o1") || lower.startsWith("o3") || lower.startsWith("o4") || lower.startsWith("o5")
                || lower.startsWith("gpt-5")) {
            return true;
        }
        Matcher m = GPT_VERSION.matcher(lower);
        return m.matches() && Integer.parseInt(m.group(1)) > 5;
    }

    /**
     * The models OpenAI refuses function tools together with reasoning on
     * Chat Completions: gpt-5.6 and everything after it, snapshot and suffixed
     * variants included. Such a request only goes through with
     * {@code reasoning_effort=none}.
     */
    public static boolean refusesToolsWithReasoningOnChat(String modelId) {
        if (modelId == null) return false;
        Matcher m = GPT_VERSION.matcher(modelId.toLowerCase());
        if (!m.matches()) return false;
        int major = Integer.parseInt(m.group(1));
        int minor = m.group(2) == null ? 0 : Integer.parseInt(m.group(2));
        return major > 5 || (major == 5 && minor >= 6);
    }
}
