package ai.mindconnect.llm.demo;

import ai.mindconnect.llm.demo.adapter.openaisdk.OpenAiSdkGateway;
import ai.mindconnect.llm.management.api.domain.LlmConfig;
import ai.mindconnect.llm.provider.spi.port.out.LlmGateway;
import ai.mindconnect.llm.runtime.api.domain.Cancellation;
import ai.mindconnect.llm.runtime.api.domain.LlmMessage;
import ai.mindconnect.llm.runtime.api.domain.LlmRequest;
import ai.mindconnect.llm.runtime.api.domain.LlmStreamChunk;
import ai.mindconnect.llm.runtime.api.port.out.LlmCallListener;

import java.util.List;

/**
 * A chat through a provider of one's own: the config names model and key, the
 * request names the config, and the answer streams to standard output.
 *
 * <p>Run with {@code OPENAI_API_KEY} set; the first argument is the question.
 * Every run is a paid call to OpenAI.
 */
public final class OwnProviderDemo {

    private OwnProviderDemo() {
    }

    public static void main(String[] args) {
        String apiKey = System.getenv("OPENAI_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            System.err.println("Set OPENAI_API_KEY to run this demo.");
            System.exit(1);
        }
        String question = args.length > 0 ? String.join(" ", args) : "Name three rivers in Germany.";

        LlmConfig config = LlmConfig.openAi("demo", "gpt-5.4-mini", apiKey);
        LlmGateway gateway = new OpenAiSdkGateway();

        LlmRequest request = LlmRequest.streaming(config.name(), List.of(
                LlmMessage.system("Answer in one short paragraph."),
                LlmMessage.user(question)));
        gateway.chatStreaming(config, request, chunk -> {
            switch (chunk) {
                case LlmStreamChunk.TextDelta delta -> System.out.print(delta.text());
                case LlmStreamChunk.Done done -> System.out.printf("%n[%s, %d tokens in, %d out]%n",
                        done.finishReason(), done.inputTokens(), done.outputTokens());
                default -> { }
            }
        }, Cancellation.none(), LlmCallListener.NOOP);
    }
}
