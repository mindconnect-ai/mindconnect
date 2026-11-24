package ai.mindconnect.agent.protocol.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import ai.mindconnect.agent.UserId;
import ai.mindconnect.agent.loop.domain.TurnMessage;
import ai.mindconnect.agent.protocol.IncompleteReason;
import ai.mindconnect.agent.protocol.Response;
import ai.mindconnect.agent.protocol.ResponseStatus;
import ai.mindconnect.agent.protocol.Session;
import ai.mindconnect.agent.protocol.api.ResponseRequest;
import ai.mindconnect.agent.protocol.item.ConversationItem;
import ai.mindconnect.agent.protocol.item.ConversationItemRecord;
import ai.mindconnect.agent.runtime.api.domain.AgentDefinition;
import ai.mindconnect.agent.runtime.api.domain.ModelSettings;
import ai.mindconnect.agent.runtime.api.domain.ToolBinding;
import ai.mindconnect.agent.runtime.api.domain.ToolCall;
import ai.mindconnect.agent.runtime.api.domain.ToolDefinition;
import ai.mindconnect.agent.runtime.api.domain.ToolOutput;
import ai.mindconnect.agent.runtime.api.port.out.Tool;

/**
 * A turn that waits for an approval ends its response INCOMPLETE with an ApprovalRequest item;
 * the ApprovalResponse as the next input continues the same turn as a new response.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class AgentRuntimeBackendApprovalTest {

    private static final String ECHO = "guarded_echo";

    private final List<String> runs = new CopyOnWriteArrayList<>();
    private BackendFixture fixture;
    private AgentRuntimeBackend backend;

    @BeforeEach
    void setUp() {
        fixture = new BackendFixture(List.of(new Tool() {
            @Override
            public ToolDefinition definition() {
                return new ToolDefinition(ECHO, "Echoes the text.",
                        Map.of("type", "object", "properties", Map.of("text", Map.of("type", "string"))));
            }

            @Override
            public ToolOutput execute(ToolCall call, ai.mindconnect.agent.runtime.api.domain.Session session) {
                runs.add(String.valueOf(call.arguments().get("text")));
                return ToolOutput.ok("ECHO");
            }
        }));
        fixture.agent(new AgentDefinition("guarded", "Asks first.", "You ask first.", ModelSettings.ofModel("any"),
                List.of(new ToolBinding(ECHO, false, Map.of("needsApproval", true))), 5, Map.of()));
        // Calls the guarded tool once, then answers "done".
        fixture.says(TurnMessage.toolCalls(List.of(new TurnMessage.ToolCall("c1", ECHO, Map.of("text", "hi")))),
                TurnMessage.assistant("done"));
        backend = fixture.backend(() -> UserId.of("alice"));
    }

    @AfterEach
    void tearDown() {
        fixture.close();
    }

    @Test
    void theResponseEndsIncompleteAndTheAnswerContinuesTheTurn() {
        Session session = backend.sessions().open("guarded");

        Response waiting = backend.responses().create(ResponseRequest.text(session.id(), "go"));

        assertThat(waiting.status()).isEqualTo(ResponseStatus.INCOMPLETE);
        assertThat(waiting.incompleteReason()).isEqualTo(IncompleteReason.WAITING_FOR_APPROVAL);
        ConversationItem.ApprovalRequest request = approvalIn(waiting);
        assertThat(request.kind()).isEqualTo(ConversationItem.ApprovalRequest.TOOL_APPROVAL);
        assertThat(request.payload()).containsEntry("name", ECHO)
                .containsEntry("arguments", Map.of("text", "hi"));
        assertThat(runs).isEmpty();

        Response done = backend.responses().create(
                ResponseRequest.approval(session.id(), request.requestId(), true));

        assertThat(done.status()).isEqualTo(ResponseStatus.COMPLETED);
        assertThat(done.id()).isNotEqualTo(waiting.id());
        assertThat(done.metadata().get("mc.turnId")).isEqualTo(waiting.metadata().get("mc.turnId"));
        assertThat(done.outputText()).isEqualTo("done");
        assertThat(runs).containsExactly("hi");
    }

    @Test
    void aDeniedCallDoesNotRun() {
        Session session = backend.sessions().open("guarded");
        Response waiting = backend.responses().create(ResponseRequest.text(session.id(), "go"));

        Response done = backend.responses().create(
                ResponseRequest.approval(session.id(), approvalIn(waiting).requestId(), false));

        assertThat(done.status()).isEqualTo(ResponseStatus.COMPLETED);
        assertThat(runs).isEmpty();
    }

    @Test
    void anAnswerToNoOpenRequestIsRefused() {
        Session session = backend.sessions().open("guarded");

        assertThatThrownBy(() -> backend.responses().create(
                ResponseRequest.approval(session.id(), "no-such-call", true)))
                .isInstanceOf(RuntimeBackendException.class)
                .hasMessageContaining("no-such-call");
    }

    private static ConversationItem.ApprovalRequest approvalIn(Response response) {
        return response.output().stream()
                .map(ConversationItemRecord::item)
                .filter(ConversationItem.ApprovalRequest.class::isInstance)
                .map(ConversationItem.ApprovalRequest.class::cast)
                .findFirst().orElseThrow();
    }
}
