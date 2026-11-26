package ai.mindconnect.llm.demo;

import ai.mindconnect.llm.demo.adapter.openaisdk.OpenAiSdkGateway;
import ai.mindconnect.llm.management.api.domain.LlmConfig;
import ai.mindconnect.llm.provider.spi.port.out.LlmGateway;
import ai.mindconnect.llm.runtime.api.domain.Cancellation;
import ai.mindconnect.llm.runtime.api.domain.LlmMessage;
import ai.mindconnect.llm.runtime.api.domain.LlmRequest;
import ai.mindconnect.llm.runtime.api.port.out.LlmCallListener;

import java.util.List;

/**
 * A provider of one's own, built on the OpenAI SDK, called directly through the
 * {@link LlmGateway} SPI, without the gateway service: the caller holds the config,
 * with model and key, and hands it in with every request.
 *
 * <p>Run with {@code OPENAI_API_KEY} set; the first argument is the question. Every
 * run is a paid call to OpenAI.
 */
public final class LlmGatewayWithOwnProviderDemo {

    private LlmGatewayWithOwnProviderDemo() {
    }

    public static void main(String[] args) {
        DemoConsole.requireOpenAiKey();
        LlmConfig config = LlmConfig.openAi("demo", "gpt-5.4-mini", System.getenv("OPENAI_API_KEY"));
        LlmGateway gateway = new OpenAiSdkGateway();

        LlmRequest request = LlmRequest.streaming(config.name(), List.of(
                LlmMessage.system("Answer in one short paragraph."),
                LlmMessage.user(DemoConsole.question(args))));
        gateway.chatStreaming(config, request, DemoConsole::print, Cancellation.none(), LlmCallListener.NOOP);
    }
}
