package ai.mindconnect.llm.demo;

import ai.mindconnect.llm.runtime.api.domain.LlmStreamChunk;

/** What every demo does at the console: check the key, take the question, print the answer. */
final class DemoConsole {

    private DemoConsole() {
    }

    /** Stops the program unless {@code OPENAI_API_KEY} is set; the key itself stays in the environment. */
    static void requireOpenAiKey() {
        String key = System.getenv("OPENAI_API_KEY");
        if (key == null || key.isBlank()) {
            System.err.println("Set OPENAI_API_KEY to run this demo.");
            System.exit(1);
        }
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
