package ai.mindconnect.agent.sdk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;

import ai.mindconnect.agent.loop.domain.LlmAnswer;
import ai.mindconnect.agent.loop.domain.TurnMessage;
import ai.mindconnect.agent.loop.domain.Usage;
import ai.mindconnect.agent.loop.port.out.TurnEventListener;
import ai.mindconnect.agent.runtime.api.domain.AgentDefinition;
import ai.mindconnect.agent.runtime.api.domain.ModelRequest;
import ai.mindconnect.agent.runtime.api.domain.ModelSettings;
import ai.mindconnect.agent.runtime.api.domain.Session;
import ai.mindconnect.agent.runtime.api.domain.SessionSpec;
import ai.mindconnect.agent.runtime.api.domain.ToolBinding;
import ai.mindconnect.agent.runtime.api.domain.ToolCall;
import ai.mindconnect.agent.runtime.api.domain.ToolDefinition;
import ai.mindconnect.agent.runtime.api.domain.ToolOutput;
import ai.mindconnect.agent.runtime.api.domain.Turn;
import ai.mindconnect.agent.runtime.api.domain.TurnId;
import ai.mindconnect.agent.runtime.api.domain.TurnRequest;
import ai.mindconnect.agent.runtime.api.domain.TurnStatus;
import ai.mindconnect.agent.UserId;
import ai.mindconnect.agent.runtime.api.port.out.ModelGateway;
import ai.mindconnect.agent.runtime.api.port.out.Tool;
import ai.mindconnect.agent.runtime.capabilities.title.SessionTitlePlugin;
import org.junit.jupiter.api.Test;

/** The SDK the way a program would use it: build, open a session, submit a turn. */
class AgentRuntimeTest {

    private static final AgentDefinition ASSISTANT = new AgentDefinition("assistant", "Helps.", "You help.",
            ModelSettings.ofModel("any"), ToolBinding.allOf("get_weather"), 5, Map.of());

    private final Deque<TurnMessage> script = new ArrayDeque<>();
    private final ModelGateway model = new ModelGateway() {
        @Override
        public synchronized LlmAnswer ask(ModelRequest request, TurnEventListener events) {
            return LlmAnswer.of(List.of(script.pop()), new Usage(10, 1));
        }

        @Override
        public void cancel(TurnId turnId) {
        }
    };

    @Test
    void a_program_builds_a_runtime_and_runs_a_turn_with_a_tool_of_its_own() throws InterruptedException {
        script.add(TurnMessage.toolCalls(List.of(new TurnMessage.ToolCall("c1", "get_weather", Map.of()))));
        script.add(TurnMessage.assistant("It is sunny."));

        try (AgentRuntime runtime = AgentRuntime.builder()
                .model(model)
                .agent(ASSISTANT)
                .tool(weather())
                .without(SessionTitlePlugin.class)
                .build()) {
            Session session = runtime.sessions().open(
                    new SessionSpec(UserId.of("ada"), null, ASSISTANT, Map.of()));
            TurnId turnId = runtime.turns().submit(TurnRequest.text(session.id(), "Weather?"));

            Turn turn = runtime.turns().find(turnId).orElseThrow();
            long deadline = System.currentTimeMillis() + 5_000;
            while (!turn.status().isFinal() && System.currentTimeMillis() < deadline) {
                Thread.sleep(10);
                turn = runtime.turns().find(turnId).orElseThrow();
            }

            assertThat(turn.status()).isEqualTo(TurnStatus.COMPLETED);
            assertThat(turn.outcome().text()).isEqualTo("It is sunny.");
        }
    }

    @Test
    void a_runtime_without_a_model_is_not_built() {
        assertThatThrownBy(() -> AgentRuntime.builder().agent(ASSISTANT).build())
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("model");
    }

    private static Tool weather() {
        return new Tool() {
            @Override
            public ToolDefinition definition() {
                return new ToolDefinition("get_weather", "The weather.", Map.of("type", "object"));
            }

            @Override
            public ToolOutput execute(ToolCall call, Session session) {
                return ToolOutput.ok("sunny");
            }
        };
    }
}
