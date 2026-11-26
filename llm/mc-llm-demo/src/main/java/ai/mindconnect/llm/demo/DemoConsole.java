package ai.mindconnect.llm.demo;

import ai.mindconnect.common.env.EnvVarResolver;
import ai.mindconnect.common.util.McEnv;
import ai.mindconnect.llm.runtime.api.domain.LlmStreamChunk;

import java.util.Map;

/** What every demo does at the console: find the key, take the question, print the answer. */
final class DemoConsole {

    private DemoConsole() {
    }

    /**
     * The real OpenAI key, from the process environment or else from the repository's
     * {@code mc.env}, so a demo runs without setting anything by hand. Stops the
     * program when neither has one.
     */
    static String openAiKey() {
        String key = McEnv.get("OPENAI_API_KEY", "");
        if (key.isBlank()) {
            System.err.println("Put OPENAI_API_KEY into mc.env or the environment to run this demo.");
            System.exit(1);
        }
        return key;
    }

    /**
     * Where a stored config's {@code ${OPENAI_API_KEY}} placeholder resolves from: the
     * same key, so the gateway service finds it in {@code mc.env} too.
     */
    static EnvVarResolver environment() {
        return EnvVarResolver.of(Map.of("OPENAI_API_KEY", openAiKey()));
    }

    static String question(String[] args) {
        return args.length > 0 ? String.join(" ", args) : "Name three rivers in Germany.";
    }

    /** Prints the text as it streams and the usage at the end. */
    static void print(LlmStreamChunk chunk) {
        switch (chunk) {
            case LlmStreamChunk.TextDelta delta -> System.out.print(delta.text());
            case LlmStreamChunk.Done done -> System.out.printf("%n[%s, %d tokens in, %d out]%n",
                    done.finishReason(), done.inputTokens(), done.outputTokens());
            default -> { }
        }
    }
}
