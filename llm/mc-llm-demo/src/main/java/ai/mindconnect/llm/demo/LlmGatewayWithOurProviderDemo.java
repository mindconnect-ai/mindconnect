package ai.mindconnect.llm.demo;

import ai.mindconnect.llm.jackson.LlmJsonModule;
import ai.mindconnect.llm.management.api.domain.LlmConfig;
import ai.mindconnect.llm.provider.spi.port.out.LlmGateway;
import ai.mindconnect.llm.providers.openai.OpenAiResponsesGateway;
import ai.mindconnect.llm.runtime.api.domain.Cancellation;
import ai.mindconnect.llm.runtime.api.domain.LlmMessage;
import ai.mindconnect.llm.runtime.api.domain.LlmRequest;
import ai.mindconnect.llm.runtime.api.port.out.LlmCallListener;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import okhttp3.OkHttpClient;

import java.util.List;

/**
 * One of our providers, OpenAI's Responses API, called directly through the
 * {@link LlmGateway} SPI, without the gateway service: the caller holds the config,
 * with model and key, and hands it in with every request. No routing, no aliases, no
 * retries; that is what {@link LlmChatWithOurProvidersDemo} adds.
 *
 * <p>Run with {@code OPENAI_API_KEY} set; the first argument is the question. Every
 * run is a paid call to OpenAI.
 */
public final class LlmGatewayWithOurProviderDemo {

    private LlmGatewayWithOurProviderDemo() {
    }

    public static void main(String[] args) {
        DemoConsole.requireOpenAiKey();
        LlmConfig config = LlmConfig.openAi("demo", "gpt-5.4-mini", System.getenv("OPENAI_API_KEY"));
        OkHttpClient http = new OkHttpClient();
        LlmGateway gateway = new OpenAiResponsesGateway(http, JsonMapper.builder()
                .addModule(new LlmJsonModule()).addModule(new JavaTimeModule()).build());

        LlmRequest request = LlmRequest.streaming(config.name(), List.of(
                LlmMessage.system("Answer in one short paragraph."),
                LlmMessage.user(DemoConsole.question(args))));
        gateway.chatStreaming(config, request, DemoConsole::print, Cancellation.none(), LlmCallListener.NOOP);
        http.dispatcher().executorService().shutdown();
        http.connectionPool().evictAll();
    }
}
