package ai.mindconnect.agent.protocol.runtime;

import ai.mindconnect.agent.builder.AgentRuntime;
import ai.mindconnect.agent.builder.AgentRuntimeBuilder;
import ai.mindconnect.agent.protocol.IncompleteReason;
import ai.mindconnect.agent.protocol.Response;
import ai.mindconnect.agent.protocol.ResponseStatus;
import ai.mindconnect.agent.protocol.Session;
import ai.mindconnect.agent.protocol.api.ResponseRequest;
import ai.mindconnect.agent.protocol.item.ConversationItem;
import ai.mindconnect.agent.protocol.item.ConversationItemRecord;
import ai.mindconnect.agent.runtime.domain.AgentDefinition;
import ai.mindconnect.agent.runtime.feature.FeatureContext;
import ai.mindconnect.agent.runtime.feature.RuntimeFeature;
import ai.mindconnect.agent.tool.AgentTool;
import ai.mindconnect.agent.tool.AgentToolId;
import ai.mindconnect.agent.tool.Tool;
import ai.mindconnect.agent.tool.ToolCallScope;
import ai.mindconnect.agent.tool.ToolFactory;
import ai.mindconnect.common.Cancellation;
import ai.mindconnect.llm.domain.FinishReason;
import ai.mindconnect.llm.domain.LlmConfig;
import ai.mindconnect.llm.domain.LlmMessage;
import ai.mindconnect.llm.domain.LlmRequest;
import ai.mindconnect.llm.domain.LlmStreamChunk;
import ai.mindconnect.llm.domain.MessageRole;
import ai.mindconnect.llm.port.in.LlmCallListener;
import ai.mindconnect.llm.port.in.LlmChat;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A turn that waits for an approval ends its response INCOMPLETE with an ApprovalRequest item;
 * the ApprovalResponse as the next input continues the same turn as a new response.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class AgentRuntimeBackendApprovalTest {

    static final List<String> RUNS = new CopyOnWriteArrayList<>();

    private AgentRuntime runtime;
    private AgentRuntimeBackend backend;

    @BeforeEach
    void setUp() {
        RUNS.clear();
        AgentDefinition base = AgentDefinition.create("guarded", "Asks first.", "You ask first.", null, "scripted");
        AgentDefinition guarded = new AgentDefinition(base.id(), base.name(), base.description(), base.group(),
                base.icon(), base.systemPrompt(), base.welcomeMessage(), base.llmConfigName(),
                base.maxIterations(), base.memoryConfig(), base.status(),
                List.of(new AgentTool(AgentToolId.random(), EchoToolFactory.NAME, null, Map.of(),
                        true, false, true, null)),
                base.responseReviewers(), base.callableAgents(), base.callableByAgents(),
                base.createdAt(), base.updatedAt());
        runtime = AgentRuntimeBuilder.useInMemoryPersistence()
                .llmConfig(LlmConfig.lmStudio("scripted", "scripted-model", "http://127.0.0.1:9"))
                .install(new ScriptedLlmFeature())
                .agentDefinition(guarded)
                .build();
        backend = new AgentRuntimeBackend(runtime.chatService(), runtime.sessionService(),
                runtime.agentDefinitions(), runtime.conversationManager(), "alice");
    }

    @AfterEach
    void tearDown() {
        runtime.close();
    }

    @Test
    void theResponseEndsIncompleteAndTheAnswerContinuesTheTurn() {
        Session session = backend.sessions().open("guarded");

        Response waiting = backend.responses().create(ResponseRequest.text(session.id(), "go"));

        assertThat(waiting.status()).isEqualTo(ResponseStatus.INCOMPLETE);
        assertThat(waiting.incompleteReason()).isEqualTo(IncompleteReason.WAITING_FOR_APPROVAL);
        ConversationItem.ApprovalRequest request = waiting.output().stream()
                .map(ConversationItemRecord::item)
                .filter(ConversationItem.ApprovalRequest.class::isInstance)
                .map(ConversationItem.ApprovalRequest.class::cast)
                .findFirst().orElseThrow();
        assertThat(request.kind()).isEqualTo(ConversationItem.ApprovalRequest.TOOL_APPROVAL);
        assertThat(request.payload()).containsEntry("name", EchoToolFactory.NAME)
                .containsEntry("arguments", Map.of("text", "hi"));
        assertThat(RUNS).isEmpty();

        Response done = backend.responses().create(
                ResponseRequest.approval(session.id(), request.requestId(), true));

        assertThat(done.status()).isEqualTo(ResponseStatus.COMPLETED);
        assertThat(done.id()).isNotEqualTo(waiting.id());
        assertThat(done.metadata().get("mc.turnId")).isEqualTo(waiting.metadata().get("mc.turnId"));
        assertThat(done.outputText()).isEqualTo("done");
        assertThat(RUNS).containsExactly("hi");
    }

    @Test
    void aDeniedCallDoesNotRun() {
        Session session = backend.sessions().open("guarded");
        Response waiting = backend.responses().create(ResponseRequest.text(session.id(), "go"));
        String requestId = ((ConversationItem.ApprovalRequest) waiting.output().stream()
                .map(ConversationItemRecord::item)
                .filter(ConversationItem.ApprovalRequest.class::isInstance)
                .findFirst().orElseThrow()).requestId();

        Response done = backend.responses().create(ResponseRequest.approval(session.id(), requestId, false));

        assertThat(done.status()).isEqualTo(ResponseStatus.COMPLETED);
        assertThat(RUNS).isEmpty();
    }

    @Test
    void anAnswerToNoOpenRequestIsRefused() {
        Session session = backend.sessions().open("guarded");

        assertThatThrownBy(() -> backend.responses().create(
                ResponseRequest.approval(session.id(), "no-such-call", true)))
                .isInstanceOf(RuntimeBackendException.class)
                .hasMessageContaining("no-such-call");
    }

    // ── fixtures ───────────────────────────────────────────────────────────

    /** A tool the tests can see run: records its text. */
    public static class EchoToolFactory implements ToolFactory {
        static final String NAME = "guarded_echo";

        @Override public String name() { return NAME; }

        @Override
        public Tool create(AgentTool agentTool, ToolCallScope scope) {
            return new Tool() {
                @Override public String name() { return NAME; }
                @Override public String description() { return "Echoes the text."; }
                @Override public Map<String, Object> parametersSchema() {
                    return Map.of("type", "object", "properties", Map.of("text", Map.of("type", "string")));
                }
                @Override public String execute(Map<String, Object> arguments) {
                    RUNS.add(String.valueOf(arguments.get("text")));
                    return "ECHO";
                }
            };
        }
    }

    static class ScriptedLlmFeature implements RuntimeFeature {
        @Override public String name() { return "scripted-llm"; }
        @Override public void configure(FeatureContext ctx) {
            ctx.decorate(LlmChat.class, original -> new ScriptedLlm());
        }
    }

    /** Calls the guarded tool once, then answers "done". */
    static class ScriptedLlm implements LlmChat {
        @Override
        public void chatStreaming(LlmRequest request, Consumer<LlmStreamChunk> handler,
                                  Cancellation cancellation, LlmCallListener listener) {
            List<LlmMessage> messages = request.messages();
            if (messages.get(messages.size() - 1).role() == MessageRole.TOOL) {
                handler.accept(new LlmStreamChunk.TextDelta("done"));
                handler.accept(new LlmStreamChunk.Done(FinishReason.STOP, 1, 1));
                return;
            }
            handler.accept(new LlmStreamChunk.ToolCallDelta(0, "call_" + System.nanoTime(),
                    EchoToolFactory.NAME, "{\"text\":\"hi\"}"));
            handler.accept(new LlmStreamChunk.Done(FinishReason.TOOL_CALLS, 1, 1));
        }
    }
}
