package ai.mindconnect.agent.protocol.client;

import ai.mindconnect.agent.protocol.IncompleteReason;
import ai.mindconnect.agent.protocol.Response;
import ai.mindconnect.agent.protocol.ResponseStatus;
import ai.mindconnect.agent.protocol.Usage;
import ai.mindconnect.agent.protocol.api.AgentResponses;
import ai.mindconnect.agent.protocol.api.ResponseRequest;
import ai.mindconnect.agent.protocol.api.SubscribeRequest;
import ai.mindconnect.agent.protocol.api.Subscription;
import ai.mindconnect.agent.protocol.api.ToolDefinition;
import ai.mindconnect.agent.protocol.event.ResponseEvent;
import ai.mindconnect.agent.protocol.item.ConversationItem;
import ai.mindconnect.agent.protocol.item.ConversationItemRecord;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the loop against a FAKE backend — the mechanic is protocol, not
 * implementation: the same loop drives the OpenAI adapter or our runtime.
 */
class ToolLoopTest {

    @Test
    void answersOpenCallAndFinishes() {
        String sessionId = "sess_1";

        AgentResponses fake = new AgentResponses() {
            int round = 0;

            @Override
            public Response create(ResponseRequest request) {
                if (round++ == 0) {
                    return response(ResponseStatus.INCOMPLETE, IncompleteReason.WAITING_FOR_TOOL_OUTPUT,
                            new ConversationItem.FunctionCall("call_1", "get_weather", Map.of("city", "Lisbon")));
                }
                // second round: the loop must feed back our tool output
                ConversationItem first = request.input().get(0);
                assertThat(first).isInstanceOf(ConversationItem.FunctionCallOutput.class);
                assertThat(((ConversationItem.FunctionCallOutput) first).output()).contains("sunny");
                return response(ResponseStatus.COMPLETED, null,
                        ConversationItem.Message.assistant("It is sunny in Lisbon."));
            }

            @Override public Optional<Response> get(String id) { return Optional.empty(); }
            @Override public boolean cancel(String id) { return false; }
            @Override public Subscription subscribe(SubscribeRequest r, Consumer<ResponseEvent> c) {
                return () -> { };
            }
        };

        ToolHandler weather = ToolHandler.of(
                new ToolDefinition("get_weather", "weather", Map.of()),
                args -> "sunny in " + args.get("city"));

        Response result = new ToolLoop(fake, List.of(weather))
                .run(ResponseRequest.text(sessionId, "weather in Lisbon?"));

        assertThat(result.status()).isEqualTo(ResponseStatus.COMPLETED);
        assertThat(result.outputText()).isEqualTo("It is sunny in Lisbon.");
    }

    @Test
    void unknownToolYieldsFailedOutputInsteadOfCrash() {
        AgentResponses fake = new AgentResponses() {
            int round = 0;

            @Override
            public Response create(ResponseRequest request) {
                if (round++ == 0) {
                    return response(ResponseStatus.INCOMPLETE, IncompleteReason.WAITING_FOR_TOOL_OUTPUT,
                            new ConversationItem.FunctionCall("call_1", "not_registered", Map.of()));
                }
                ConversationItem.FunctionCallOutput out = (ConversationItem.FunctionCallOutput) request.input().get(0);
                assertThat(out.failed()).isTrue();
                return response(ResponseStatus.COMPLETED, null, ConversationItem.Message.assistant("ok"));
            }

            @Override public Optional<Response> get(String id) { return Optional.empty(); }
            @Override public boolean cancel(String id) { return false; }
            @Override public Subscription subscribe(SubscribeRequest r, Consumer<ResponseEvent> c) {
                return () -> { };
            }
        };

        Response result = new ToolLoop(fake, List.of())
                .run(ResponseRequest.text("sess_2", "hi"));

        assertThat(result.status()).isEqualTo(ResponseStatus.COMPLETED);
    }

    private static Response response(ResponseStatus status, IncompleteReason reason, ConversationItem... items) {
        List<ConversationItemRecord> output = new java.util.ArrayList<>();
        for (int i = 0; i < items.length; i++) {
            output.add(new ConversationItemRecord("item-" + (i + 1), i + 1, items[i]));
        }
        return new Response("resp_1", "conv_1", "sess_1", "fake",
                status, reason, null, null, output, Usage.ZERO, null, Map.of(),
                Instant.EPOCH, Instant.EPOCH);
    }
}
