package ai.mindconnect.agent.protocol.runtime;

import ai.mindconnect.agent.builder.AgentRuntime;
import ai.mindconnect.agent.builder.AgentRuntimeBuilder;
import ai.mindconnect.agent.domain.AgentDefinition;
import ai.mindconnect.agent.tool.AgentTool;
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
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Live parity examples — the SAME scenarios as the OpenAI backend's
 * {@code OpenAiHostedToolsExampleTest}, against the MindConnect runtime.
 * What OpenAI hosts, the runtime runs as REGISTERED tools of the agent
 * definition; through the protocol both appear as identical
 * FunctionCall/Output item pairs:
 *
 * <ul>
 *   <li>web search      → {@code web_search}/{@code web_read} (Tavily key)</li>
 *   <li>code execution  → {@code code_execute} (podman/docker container)</li>
 *   <li>upload + ask    → {@code RuntimeFileQaExampleTest} (vector_search)</li>
 *   <li>upload + compute → retrieval chained with {@code code_execute}</li>
 * </ul>
 *
 * Chat and embeddings come from {@code mc.env}; each scenario additionally
 * gates itself on what it needs (a Tavily key for web search, a container
 * runtime for code execution). Live-model examples can be nondeterministic — a rare red run
 * usually means the model answered off-script, not that the adapter broke.
 */
class RuntimeToolsExampleTest {

    /** Only runs when the configured model server answers — see {@link TestModels}. */
    static boolean modelIsUp() {
        return TestModels.available();
    }

    static boolean hasWebSearch() {
        return modelIsUp() && !McEnv.get("TAVILY_API_KEY", "").isBlank();
    }

    static boolean hasCodeRuntime() {
        if (!modelIsUp()) return false;
        for (String bin : List.of("podman", "docker")) {
            try {
                Process p = new ProcessBuilder(bin, "info").redirectErrorStream(true).start();
                if (p.waitFor(10, TimeUnit.SECONDS) && p.exitValue() == 0) return true;
            } catch (Exception ignored) {
                // binary missing — try the next one
            }
        }
        return false;
    }

    // ── 1. Web search as a registered tool ──────────────────────────────────

    @Test
    @EnabledIf("hasWebSearch")
    void webSearch() throws Exception {
        try (AgentRuntime runtime = runtime()) {
            AgentDefinition researcher = AgentDefinition.create(runtime.namespace(), "researcher",
                    "Searches the web and reports briefly.",
                    "Answer briefly. You MUST use web_search for current facts; "
                            + "use web_read on the most promising result when needed.",
                    null, "chat");
            runtime.agentDefinitions().save(researcher.withTools(List.of(
                    AgentTool.of(researcher.id(), "web_search"),
                    AgentTool.of(researcher.id(), "web_read"))));

            AgentRuntimeBackend backend = backend(runtime);
            Session session = backend.open(runtime.namespace().value(), "researcher");

            Response r = backend.create(ResponseRequest.text(session.id(),
                    "Search the web: what is the latest stable OpenJDK release?"));

            assertThat(r.status()).isEqualTo(ResponseStatus.COMPLETED);
            assertThat(r.outputText()).isNotBlank();
            assertThat(toolCalls(r)).contains("web_search");
            System.out.println("[runtime web_search] " + r.outputText());
        }
    }

    // ── 2. Code execution in a session container ────────────────────────────

    @Test
    @EnabledIf("hasCodeRuntime")
    void codeExecution() throws Exception {
        try (AgentRuntime runtime = runtime()) {
            // the larger model follows the tool contract reliably; mini
            // occasionally hallucinates a digest instead of computing one
            AgentDefinition analyst = AgentDefinition.create(runtime.namespace(), "analyst",
                    "Computes with real code.",
                    "You are unable to compute anything yourself — hashes, sums, anything: "
                            + "an answer from memory WILL be wrong. Always call the "
                            + "code_execute tool (language python) and report only its result.",
                    null, "chat");
            runtime.agentDefinitions().save(analyst.withTools(List.of(
                    AgentTool.of(analyst.id(), "code_execute"))));

            AgentRuntimeBackend backend = backend(runtime);
            Session session = backend.open(runtime.namespace().value(), "analyst");

            // Not memorizable — the model MUST actually run code for this:
            Response r = backend.create(ResponseRequest.text(session.id(),
                    "Compute the SHA-256 hex digest of the ASCII string 'mindconnect' with Python."));

            assertThat(r.status()).isEqualTo(ResponseStatus.COMPLETED);
            assertThat(r.outputText()).contains("c035caf4ab7f39e2");
            assertThat(toolCalls(r)).contains("code_execute");
            System.out.println("[runtime code] " + r.outputText());
        }
    }

    // ── 4. Uploaded data analyzed with executed code (retrieval → compute) ──

    @Test
    @EnabledIf("hasCodeRuntime")
    void uploadCsvAndAnalyzeWithCode() throws Exception {
        try (AgentRuntime runtime = runtime()) {
            AgentDefinition analyst = AgentDefinition.create(runtime.namespace(), "data-analyst",
                    "Analyzes uploaded data with real code.",
                    "The user's uploaded documents are searchable with vector_search. "
                            + "For any calculation you MUST run python via code_execute — "
                            + "never calculate in your head. Report only the number.",
                    null, "chat");
            runtime.agentDefinitions().save(analyst.withTools(List.of(
                    AgentTool.of(analyst.id(), "code_execute"))));

            AgentRuntimeBackend backend = backend(runtime);
            Session session = backend.open(runtime.namespace().value(), "data-analyst");

            StoredFile csv = backend.files().upload("sales.csv", "text/csv",
                    "region,revenue\nnorth,10\nsouth,20\nwest,30\n"
                            .getBytes(StandardCharsets.UTF_8));

            var question = new ConversationItem.Message(Role.USER, List.of(
                    new ContentPart.Text("Find the revenue table in my uploaded sales.csv "
                            + "and compute the total revenue with python."),
                    new ContentPart.Document(
                            new ContentPart.MediaSource.FileId(csv.id()), "sales.csv")));
            Response r = backend.create(
                    new ResponseRequest(session.id(), List.of(question), false, List.of()));

            assertThat(r.status()).isEqualTo(ResponseStatus.COMPLETED);
            assertThat(r.outputText()).contains("60");
            assertThat(toolCalls(r)).contains("code_execute");
            System.out.println("[runtime csv] tools=" + toolCalls(r));
            System.out.println("[runtime csv] " + r.outputText());
        }
    }

    // ── shared wiring ───────────────────────────────────────────────────────

    private static AgentRuntime runtime() {
        return AgentRuntimeBuilder.useInMemoryPersistence()
                .llmConfig(local("chat", TestModels.chatModel(),
                        TestModels.chatApiKey(), LlmConfigType.CHAT))
                .llmConfig(local("embeddings", TestModels.embeddingModel(),
                        TestModels.embeddingApiKey(), LlmConfigType.EMBEDDING))
                .defaultLlmConfigName("chat")
                .tavilyApiKey(McEnv.get("TAVILY_API_KEY", ""))
                .build();
    }

    private static AgentRuntimeBackend backend(AgentRuntime runtime) {
        return new AgentRuntimeBackend(runtime.chatService(), runtime.sessionService(),
                runtime.agentDefinitions(), runtime.conversationManager(), "example-user")
                .withFiles(runtime.fileStore(), runtime::attachStored);
    }

    private static List<String> toolCalls(Response r) {
        return r.output().stream()
                .map(ConversationItemRecord::item)
                .filter(ConversationItem.FunctionCall.class::isInstance)
                .map(item -> ((ConversationItem.FunctionCall) item).name())
                .toList();
    }

    /** Model, URL and key come from mc.env — local by default, no cost. */
    private static LlmConfig local(String name, String model, String apiKey, LlmConfigType type) {
        return new LlmConfig(UUID.randomUUID(), name, LlmProvider.LM_STUDIO, model,
                TestModels.baseUrl(), apiKey, 0.2, 2048, Map.of(), 128_000,
                false, null, null, null, type);
    }
}
