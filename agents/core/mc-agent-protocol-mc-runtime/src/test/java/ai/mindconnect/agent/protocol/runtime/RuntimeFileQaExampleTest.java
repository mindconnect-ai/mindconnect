package ai.mindconnect.agent.protocol.runtime;

import ai.mindconnect.agent.builder.AgentRuntime;
import ai.mindconnect.agent.builder.AgentRuntimeBuilder;
import ai.mindconnect.agent.domain.AgentDefinition;
import ai.mindconnect.agent.protocol.Response;
import ai.mindconnect.agent.protocol.ResponseStatus;
import ai.mindconnect.agent.protocol.Session;
import ai.mindconnect.agent.protocol.StoredFile;
import ai.mindconnect.agent.protocol.api.ResponseRequest;
import ai.mindconnect.agent.protocol.item.ContentPart;
import ai.mindconnect.agent.protocol.item.ConversationItem;
import ai.mindconnect.agent.protocol.item.ConversationItemRecord;
import ai.mindconnect.agent.protocol.item.Role;
import ai.mindconnect.common.util.McEnv;
import ai.mindconnect.llm.domain.LlmConfig;
import ai.mindconnect.llm.domain.LlmConfigType;
import ai.mindconnect.llm.domain.LlmProvider;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Live parity example: upload a file and ask against it — the SAME caller
 * code as the OpenAI backend's example 3, now against the Mindconnect
 * runtime. The mechanics differ by design (OpenAI stuffs the document into
 * context; the runtime ingests it into the session's vector store and the
 * agent retrieves via {@code vector_search}) — the protocol hides that.
 *
 * <p>Chat and embeddings come from {@code mc.env} ({@link TestModels}) — a
 * local server by default, so no keys and no cost; skipped when it is not up.
 */
@EnabledIf("modelIsUp")
class RuntimeFileQaExampleTest {

    /** Only runs when the configured model server answers — see {@link TestModels}. */
    static boolean modelIsUp() {
        return TestModels.available();
    }

    @Test
    void uploadAndAskAgainstFile() throws Exception {
        try (AgentRuntime runtime = AgentRuntimeBuilder.useInMemoryPersistence()
                .llmConfig(local("chat", TestModels.chatModel(),
                        TestModels.chatApiKey(), LlmConfigType.CHAT))
                .llmConfig(local("embeddings", TestModels.embeddingModel(),
                        TestModels.embeddingApiKey(), LlmConfigType.EMBEDDING))
                .defaultLlmConfigName("chat")
                .build()) {

            AgentDefinition reader = AgentDefinition.create(runtime.namespace(), "reader",
                    "Answers questions about uploaded documents.",
                    "Answer questions about the user's uploaded documents by searching them "
                            + "with the vector_search tool. Be brief and cite what you find.",
                    null, "chat");
            runtime.agentDefinitions().save(reader);

            AgentRuntimeBackend backend = new AgentRuntimeBackend(
                    runtime.chatService(), runtime.sessionService(), runtime.agentDefinitions(),
                    runtime.conversationManager(), "example-user")
                    .withFiles(runtime.fileStore(), runtime::attachStored);

            Session session = backend.open(runtime.namespace().value(), "reader");

            // Same three lines as the OpenAI example — that is the point:
            StoredFile file = backend.files().upload("notes.txt", "text/plain",
                    ("Project Phoenix status meeting, 2026-08-19. Decisions: the launch code "
                            + "is MC-4711; rollout starts in October; Lisbon is the pilot region.")
                            .getBytes(StandardCharsets.UTF_8));

            var question = new ConversationItem.Message(Role.USER, List.of(
                    new ContentPart.Text("What is the launch code mentioned in my notes?"),
                    new ContentPart.Document(
                            new ContentPart.MediaSource.FileId(file.id()), "notes.txt")));
            Response r = backend.create(
                    new ResponseRequest(session.id(), List.of(question), false, List.of()));

            assertThat(r.status()).isEqualTo(ResponseStatus.COMPLETED);
            assertThat(r.outputText()).contains("4711");

            // the retrieval is visible as a normal tool-call item pair:
            List<String> toolCalls = r.output().stream()
                    .map(ConversationItemRecord::item)
                    .filter(ConversationItem.FunctionCall.class::isInstance)
                    .map(item -> ((ConversationItem.FunctionCall) item).name())
                    .toList();
            System.out.println("[runtime file q&a] tools=" + toolCalls);
            System.out.println("[runtime file q&a] " + r.outputText());
        }
    }

    /** Model, URL and key come from mc.env — local by default, no cost. */
    private static LlmConfig local(String name, String model, String apiKey, LlmConfigType type) {
        return new LlmConfig(UUID.randomUUID(), name, LlmProvider.LM_STUDIO, model,
                TestModels.baseUrl(), apiKey, 0.2, 2048, Map.of(), 128_000,
                false, null, null, null, type);
    }
}
