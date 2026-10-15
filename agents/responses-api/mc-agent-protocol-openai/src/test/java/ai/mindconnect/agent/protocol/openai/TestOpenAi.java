package ai.mindconnect.agent.protocol.openai;

import ai.mindconnect.common.util.McEnv;

/**
 * Whether the tests in this module may talk to the real OpenAI API.
 *
 * <p>Two conditions, both from {@code mc.env}: an explicit
 * {@code TEST_OPENAI=true} and a usable {@code OPENAI_API_KEY}. The opt-in
 * exists because these calls cost money and depend on the network — a normal
 * {@code mvn install} should neither spend nor break on a hiccup. Everything
 * that can be proven locally runs against a local model instead; only what is
 * genuinely OpenAI-specific (hosted tools, the Responses wire format) lives
 * here.
 */
final class TestOpenAi {

    private TestOpenAi() { }

    static boolean enabled() {
        return "true".equalsIgnoreCase(McEnv.get("TEST_OPENAI", "false")) && !apiKey().isBlank();
    }

    static String apiKey() {
        return McEnv.get("OPENAI_API_KEY", "");
    }

    static String model() {
        return McEnv.get("TEST_OPENAI_MODEL", "gpt-5-mini");
    }
}
