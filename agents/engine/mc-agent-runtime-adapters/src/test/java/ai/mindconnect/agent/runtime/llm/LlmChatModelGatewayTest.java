package ai.mindconnect.agent.runtime.llm;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import ai.mindconnect.agent.loop.domain.ContentPart;
import ai.mindconnect.agent.loop.domain.LlmAnswer;
import ai.mindconnect.agent.loop.domain.TurnMessage;
import ai.mindconnect.agent.runtime.api.domain.ModelRequest;
import ai.mindconnect.agent.runtime.api.port.out.MediaReader;
import ai.mindconnect.agent.runtime.api.domain.ModelSettings;
import ai.mindconnect.agent.runtime.api.domain.TurnId;
import ai.mindconnect.llm.runtime.api.domain.LlmCallEvent;
import ai.mindconnect.llm.runtime.api.domain.LlmStreamChunk;
import ai.mindconnect.llm.runtime.api.port.in.LlmChat;

class LlmChatModelGatewayTest {

    private static final LlmCallEvent REPORTED = new LlmCallEvent(Instant.EPOCH, 12, "config", "model", 10, 1,
            "stop", "{\"input\":\"Hi\"}", List.of("event: done"), null, null, null);

    @Test
    void what_the_gateway_reports_about_a_provider_call_is_heard_with_the_turn_it_was_made_for() {
        LlmChat chat = (request, handler, cancellation, listener) -> {
            handler.accept(new LlmStreamChunk.TextDelta("Hello."));
            listener.onCall(REPORTED);
        };
        List<String> heard = new ArrayList<>();
        LlmChatModelGateway gateway = new LlmChatModelGateway(chat, new ObjectMapper(),
                (turnId, event) -> heard.add(turnId.value() + " " + event.requestJson()),
                model -> java.util.Optional.empty(), MediaReader.none());

        LlmAnswer answer = gateway.ask(new ModelRequest(TurnId.of("t1"), ModelSettings.ofModel("model"),
                "You help.", List.of(TurnMessage.user(List.of(new ContentPart.Text("Hi")))), List.of()), event -> { });

        assertThat(answer.messages()).extracting(TurnMessage::text).containsExactly("Hello.");
        assertThat(heard).containsExactly("t1 {\"input\":\"Hi\"}");
    }
}
