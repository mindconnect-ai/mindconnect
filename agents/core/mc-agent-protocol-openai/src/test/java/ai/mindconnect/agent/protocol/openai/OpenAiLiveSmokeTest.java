package ai.mindconnect.agent.protocol.openai;

import ai.mindconnect.agent.protocol.Response;
import ai.mindconnect.agent.protocol.ResponseStatus;
import ai.mindconnect.agent.protocol.Session;
import ai.mindconnect.agent.protocol.api.ResponseRequest;
import ai.mindconnect.agent.protocol.api.ToolDefinition;
import ai.mindconnect.agent.protocol.client.ToolHandler;
import ai.mindconnect.agent.protocol.client.ToolLoop;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Talks to the real OpenAI API — opt in with {@code TEST_OPENAI=true} plus a key. */
@EnabledIf("openAiEnabled")
class OpenAiLiveSmokeTest {

    static boolean openAiEnabled() {
        return TestOpenAi.enabled();
    }

    private final OpenAiResponsesBackend backend =
            new OpenAiResponsesBackend(TestOpenAi.apiKey())
                    .register(PseudoAgent.of("echo", TestOpenAi.model(),
                            "Answer with one short sentence."));

    @Test
    void blockingChat() {
        Session session = backend.openSessionForAgent("smoke", "echo");
        Response r = backend.create(ResponseRequest.text(session.id(), "Wer ist Justin Biber"));
        System.out.println("Response:" + r.outputText());
        assertThat(r.status()).isEqualTo(ResponseStatus.COMPLETED);
        assertThat(r.outputText()).isNotBlank();
    }

    @Test
    void toolLoopExecutesLocalTool() {
        Session session = backend.openSessionForAgent("smoke", "echo");
        ToolHandler weather = ToolHandler.of(
                new ToolDefinition("get_weather", "Current weather for a city",
                        Map.of("type", "object",
                                "properties", Map.of("city", Map.of("type", "string")),
                                "required", List.of("city"))),
                args -> "22 degrees and sunny in " + args.get("city"));

        Response r = new ToolLoop(backend.responses(), List.of(weather)).run(
                ResponseRequest.text(session.id(), "What is the weather in Lisbon? Use the tool."));

        assertThat(r.status()).isEqualTo(ResponseStatus.COMPLETED);
        assertThat(r.outputText()).containsIgnoringCase("sunny");
    }
}
