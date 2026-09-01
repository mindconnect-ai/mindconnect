package ai.mindconnect.agent.builder.lmstudio;

import ai.mindconnect.agent.builder.AgentRuntime;
import ai.mindconnect.agent.builder.AgentRuntimeBuilder;
import ai.mindconnect.agent.domain.AgentDefinition;
import ai.mindconnect.agent.memory.domain.MemoryConfig;
import ai.mindconnect.agent.tool.AgentTool;
import ai.mindconnect.common.Namespace;
import ai.mindconnect.llm.domain.LlmConfig;
import ai.mindconnect.message.domain.ConversationHistory;
import ai.mindconnect.message.domain.Message;
import ai.mindconnect.message.domain.MessageType;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Shared plumbing of the LM Studio integration tests. The tests run against
 * the REAL local LM Studio ({@code http://localhost:1234}, override with the
 * {@code LM_STUDIO_URL} env var) and SKIP themselves (JUnit assumption) when
 * it is not running or no tool-capable LLM is loaded — a clean build never
 * fails for a missing LM Studio.
 */
final class LmStudioSupport {

    static final String BASE_URL = System.getenv().getOrDefault("LM_STUDIO_URL", "http://localhost:1234");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Obeys instructions well enough for deterministic tool-call tests. */
    static final String ROBOT_PROMPT = """
            You are a tool-calling test robot. Follow the user's instructions EXACTLY.
            When the user asks you to call tools, call exactly those tools with exactly
            the given arguments — never substitute, never skip, never ask questions.
            After the tools return, answer with one short sentence quoting their output.
            When the user asks a plain question, answer briefly without any tool.""";

    private LmStudioSupport() { }

    /** The loaded, tool-capable LLM's id — or null (callers assume-skip). */
    static String loadedToolModel() {
        try {
            HttpResponse<String> response = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(2)).build()
                    .send(HttpRequest.newBuilder(URI.create(BASE_URL + "/api/v0/models"))
                                    .timeout(Duration.ofSeconds(3)).GET().build(),
                            HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) return null;
            for (JsonNode model : MAPPER.readTree(response.body()).path("data")) {
                boolean loaded = "loaded".equals(model.path("state").asText());
                boolean llm = "llm".equals(model.path("type").asText());
                boolean tools = false;
                for (JsonNode cap : model.path("capabilities")) {
                    tools |= "tool_use".equals(cap.asText());
                }
                if (loaded && llm && tools) return model.path("id").asText();
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    static void assumeLmStudio() {
        assumeTrue(loadedToolModel() != null,
                "LM Studio is not running at " + BASE_URL + " or no tool-capable LLM is loaded");
    }

    /** An in-memory runtime whose one agent talks to the local LM Studio. */
    static AgentRuntime runtime(String agentName, String systemPrompt,
                                MemoryConfig memoryConfig, List<AgentTool> tools) {
        String model = loadedToolModel();
        // "local" is the builder's default namespace — openSession looks there.
        AgentDefinition base = AgentDefinition.create(new Namespace("local"), agentName,
                "integration test agent", systemPrompt, null, "it-llm");
        AgentDefinition def = new AgentDefinition(base.id(), base.namespace(), base.name(),
                base.description(), base.group(), base.icon(), base.systemPrompt(), base.welcomeMessage(),
                base.llmConfigName(),
                base.maxIterations(), memoryConfig != null ? memoryConfig : base.memoryConfig(),
                base.status(),
                tools.stream().map(t -> new AgentTool(t.id(), base.id(), t.name(), t.description(),
                        t.overrides(), t.enabled(), t.deferred(), t.needsApproval(), t.maxResultChars()))
                        .toList(),
                base.responseReviewers(), base.toolSearch(), base.createdAt(), base.updatedAt());
        return AgentRuntimeBuilder.useInMemoryPersistence()
                .llmConfig(LlmConfig.lmStudio("it-llm", model, BASE_URL))
                .agentDefinition(def)
                .build();
    }

    static AgentTool tool(String name, boolean needsApproval) {
        return new AgentTool(UUID.randomUUID(), null, name, null, Map.of(), true, false, needsApproval);
    }

    // ── conversation helpers ────────────────────────────────────────────────

    static ConversationHistory history(AgentRuntime runtime, UUID conversationId) {
        return runtime.conversationManager().loadCompleteHistory(conversationId);
    }

    static List<Message> ofType(ConversationHistory history, MessageType type) {
        return history.messages().stream().filter(m -> m.type() == type).toList();
    }

    /** Polls the conversation until {@code condition} holds; false on timeout. */
    static boolean await(AgentRuntime runtime, UUID conversationId,
                         Predicate<ConversationHistory> condition, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.test(history(runtime, conversationId))) return true;
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return condition.test(history(runtime, conversationId));
    }

    /**
     * The first open approval question of the session, or null. Open
     * questions live ONLY in the ToolApprovalStore now (the gate parks the
     * tool task there) — nothing about them is a conversation message.
     */
    static ai.mindconnect.agent.service.approval.ToolApproval openApproval(
            AgentRuntime runtime, UUID rootSessionId) {
        var open = runtime.approvalStore().openForRoot(rootSessionId);
        return open.isEmpty() ? null : open.get(0);
    }

    /** Polls until {@code condition} holds; false on timeout. */
    static boolean awaitTrue(java.util.function.BooleanSupplier condition, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) return true;
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return condition.getAsBoolean();
    }
}
