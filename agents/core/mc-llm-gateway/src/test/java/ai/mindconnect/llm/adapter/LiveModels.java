package ai.mindconnect.llm.adapter;

import ai.mindconnect.common.util.McEnv;

/**
 * Which hosted models the live tests talk to, and whether they run at all.
 * Configured in the repository's {@code mc.env} or the process environment,
 * the model variables being the ones the seeded configs use:
 *
 * <pre>
 * LLM_TEST=true                          # off by default: nobody calls a provider by accident
 * OPENAI_API_KEY=…    OPENAI_MODEL=gpt-5.4-mini
 * ANTHROPIC_API_KEY=… CLAUDE_MODEL=claude-sonnet-4-6
 * GEMINI_API_KEY=…    GEMINI_MODEL=gemini-3.5-flash
 * </pre>
 *
 * <p>A provider's tests run only with {@code LLM_TEST=true} and that
 * provider's key; the others stay skipped, so a key for one vendor is enough
 * to check one adapter.
 */
public final class LiveModels {

    private LiveModels() { }

    /** Whether the tests of the provider behind {@code apiKeyVariable} run. */
    public static boolean enabled(String apiKeyVariable) {
        return "true".equalsIgnoreCase(McEnv.get("LLM_TEST", "false")) && !apiKey(apiKeyVariable).isBlank();
    }

    public static String apiKey(String apiKeyVariable) {
        return McEnv.get(apiKeyVariable, "");
    }

    public static String openAiModel() {
        return McEnv.get("OPENAI_MODEL", "gpt-5.4-mini");
    }

    public static String claudeModel() {
        return McEnv.get("CLAUDE_MODEL", "claude-sonnet-4-6");
    }

    public static String geminiModel() {
        return McEnv.get("GEMINI_MODEL", "gemini-3.5-flash");
    }
}
