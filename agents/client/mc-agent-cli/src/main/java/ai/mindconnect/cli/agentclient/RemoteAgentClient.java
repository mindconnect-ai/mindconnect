package ai.mindconnect.cli.agentclient;

import ai.mindconnect.agent.domain.AgentDefinition;
import ai.mindconnect.agent.domain.AgentSession;
import ai.mindconnect.agent.memory.domain.WorkingMemory;
import ai.mindconnect.agent.domain.StreamEvent;
import ai.mindconnect.common.Namespace;
import ai.mindconnect.message.domain.Message;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.*;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

public class RemoteAgentClient implements AgentClient {

    private static final MediaType JSON = MediaType.get("application/json");
    private static final MediaType TEXT = MediaType.get("text/plain");

    private final String baseUrl;
    private final OkHttpClient http;
    private final ObjectMapper mapper;
    /**
     * Active chat SSE calls keyed by sessionId. Lets {@link #cancelChat} abort
     * the local stream immediately rather than waiting for the server's
     * cooperative cancel to flush a final {@code Done} event.
     */
    private final Map<UUID, Call> activeChatCalls = new ConcurrentHashMap<>();

    public RemoteAgentClient(String baseUrl, OkHttpClient http, ObjectMapper mapper) {
        this.baseUrl = baseUrl.replaceAll("/$", "");
        this.http = http;
        this.mapper = mapper;
    }

    // CLI does not expose agent CRUD — create/update happens via the
    // admin UI / REST API. RemoteAgentClient therefore only implements
    // the read/list/chat/memory surface of AgentClient.

    @Override
    public Optional<AgentDefinition> findAgent(Namespace namespace, UUID agentDefinitionId) {
        Request req = new Request.Builder()
                .url(baseUrl + "/api/agents/" + agentDefinitionId
                        + "?namespace=" + namespace.value())
                .get().build();
        try (Response resp = http.newCall(req).execute()) {
            if (resp.code() == 404) return Optional.empty();
            assertOk(resp);
            return Optional.of(mapper.readValue(resp.body().string(), AgentDefinition.class));
        } catch (Exception e) {
            throw new RuntimeException("Remote call failed: " + e.getMessage(), e);
        }
    }

    @Override
    public List<AgentDefinition> listAgents(Namespace namespace) {
        Request req = new Request.Builder()
                .url(baseUrl + "/api/agents?namespace=" + namespace.value())
                .get().build();
        return execute(req, new TypeReference<>() {
        });
    }

    // --- RunSessionUseCase ---

    @Override
    public AgentSession startSession(UUID agentDefinitionId, Namespace namespace, String userId) {
        String body = toJson(Map.of(
                "agentId", agentDefinitionId,
                "namespace", namespace.value(),
                "userId", userId));
        Request req = new Request.Builder()
                .url(baseUrl + "/api/sessions")
                .post(RequestBody.create(body, JSON)).build();
        return execute(req, AgentSession.class);
    }

    @Override
    public List<AgentSession> listSessions(UUID agentDefinitionId, Namespace namespace, String userId) {
        String url = baseUrl + "/api/sessions?agentId=" + agentDefinitionId
                + "&namespace=" + namespace.value()
                + "&userId=" + userId;
        Request req = new Request.Builder().url(url).get().build();
        return execute(req, new TypeReference<>() {
        });
    }

    @Override
    public List<Message> loadHistory(UUID sessionId) {
        Request req = new Request.Builder()
                .url(baseUrl + "/api/sessions/" + sessionId + "/history")
                .get().build();
        return execute(req, new TypeReference<>() {
        });
    }

    @Override
    public String chat(UUID sessionId, String userMessage, Consumer<StreamEvent> eventHandler) {
        Request req = new Request.Builder()
                .url(baseUrl + "/api/sessions/" + sessionId + "/chat")
                .post(RequestBody.create(userMessage, TEXT)).build();

        StringBuilder full = new StringBuilder();
        Call call = http.newCall(req);
        activeChatCalls.put(sessionId, call);
        try (Response response = call.execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new RuntimeException("Server error: " + response.code());
            }
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(response.body().byteStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.startsWith("data:")) continue;
                    String json = line.substring(5).trim();
                    JsonNode node = mapper.readTree(json);
                    StreamEvent event = decodeFrame(node, full);
                    if (event != null) {
                        eventHandler.accept(event);
                        if (event instanceof StreamEvent.Done) break;
                    }
                }
            }
        } catch (Exception e) {
            // A user-initiated cancel hard-aborts the call; OkHttp throws then,
            // which is expected — return whatever we streamed so far.
            if (call.isCanceled()) return full.toString();
            throw new RuntimeException("Chat failed: " + e.getMessage(), e);
        } finally {
            activeChatCalls.remove(sessionId, call);
        }
        return full.toString();
    }

    /**
     * Decodes a single SSE frame into a {@link StreamEvent}. {@code topLevelText}
     * is the running buffer of streamed top-level token text; only top-level
     * Token / ResponseRevised frames write into it. For frames decoded recursively
     * inside a {@code sub_agent_event}, callers pass {@code null} so sub-agent
     * tokens don't pollute the parent's response text.
     */
    private StreamEvent decodeFrame(JsonNode node, StringBuilder topLevelText) {
        String type = node.path("type").asText("");
        return switch (type) {
            case "token" -> {
                String text = node.path("text").asText("");
                if (topLevelText != null) topLevelText.append(text);
                yield new StreamEvent.Token(text);
            }
            case "tool_call_started" -> {
                String toolName = node.path("toolName").asText();
                Map<String, Object> args = mapper.convertValue(
                        node.path("arguments"), new TypeReference<>() {});
                yield new StreamEvent.ToolCallStarted(toolName, args);
            }
            case "tool_call_result" -> {
                String toolName = node.path("toolName").asText();
                String result = node.path("result").asText();
                long durationMs = node.path("durationMs").asLong(0);
                yield new StreamEvent.ToolCallResult(toolName, result, durationMs);
            }
            case "tool_call_failed" -> {
                String toolName = node.path("toolName").asText();
                String error = node.path("error").asText("");
                long durationMs = node.path("durationMs").asLong(0);
                yield new StreamEvent.ToolCallFailed(toolName, error, durationMs);
            }
            case "asking_llm" -> new StreamEvent.AskingLlm();
            case "reviewing" -> new StreamEvent.Reviewing(
                    node.path("reviewerName").asText(""));
            case "reviewer_decision" -> {
                String reviewerName = node.path("reviewerName").asText("");
                String verdictRaw = node.path("verdict").asText("PASSED");
                StreamEvent.ReviewerVerdict verdict;
                try {
                    verdict = StreamEvent.ReviewerVerdict.valueOf(verdictRaw);
                } catch (IllegalArgumentException ex) {
                    verdict = StreamEvent.ReviewerVerdict.PASSED;
                }
                yield new StreamEvent.ReviewerDecision(reviewerName, verdict);
            }
            case "response_revised" -> {
                String finalText = node.path("finalText").asText("");
                String reason = node.path("reason").asText("");
                boolean blocked = node.path("blocked").asBoolean(false);
                // Replace the locally-streamed text with the revised one
                // so the return value of chat() reflects what the user saw.
                if (topLevelText != null) {
                    topLevelText.setLength(0);
                    topLevelText.append(finalText);
                }
                yield new StreamEvent.ResponseRevised(finalText, reason, blocked);
            }
            case "done" -> new StreamEvent.Done();
            case "sub_agent_started" -> {
                UUID taskId = UUID.fromString(node.path("taskId").asText());
                String agentName = node.path("agentName").asText("");
                int depth = node.path("depth").asInt(0);
                UUID subSessionId = node.path("subSessionId").isMissingNode()
                        || node.path("subSessionId").isNull()
                        ? null : UUID.fromString(node.path("subSessionId").asText());
                String input = node.path("text").isMissingNode() || node.path("text").isNull()
                        ? null : node.path("text").asText();
                yield new StreamEvent.SubAgentStarted(taskId, agentName, depth, subSessionId, input);
            }
            case "sub_agent_done" -> {
                UUID taskId = UUID.fromString(node.path("taskId").asText());
                String agentName = node.path("agentName").asText("");
                UUID subSessionId = UUID.fromString(node.path("subSessionId").asText());
                String finalText = node.path("finalText").isMissingNode()
                        || node.path("finalText").isNull()
                        ? null : node.path("finalText").asText();
                yield new StreamEvent.SubAgentDone(taskId, agentName, subSessionId, finalText);
            }
            case "sub_agent_error" -> {
                UUID taskId = UUID.fromString(node.path("taskId").asText());
                String agentName = node.path("agentName").asText("");
                String error = node.path("error").asText("");
                yield new StreamEvent.SubAgentError(taskId, agentName, error);
            }
            case "sub_agent_event" -> {
                UUID taskId = UUID.fromString(node.path("taskId").asText());
                JsonNode innerNode = node.path("inner");
                if (innerNode.isMissingNode() || innerNode.isNull()) yield null;
                StreamEvent inner = decodeFrame(innerNode, null);
                if (inner == null) yield null;
                yield new StreamEvent.SubAgentEvent(taskId, inner);
            }
            case "approval_requested" -> {
                String origin = node.path("subSessionId").asText(null);
                yield new StreamEvent.ApprovalRequested(
                        node.path("taskId").asText(null),
                        node.path("text").asText(null),
                        node.path("toolName").asText(""),
                        Map.of(),
                        origin == null || origin.isBlank() ? null : UUID.fromString(origin),
                        node.path("error").asText(null));
            }
            default -> null;
        };
    }

    @Override
    public boolean cancelChat(UUID sessionId) {
        // Send the cooperative cancel to the server first so the turn loop exits
        // and stops doing work as soon as it can.
        Request req = new Request.Builder()
                .url(baseUrl + "/api/sessions/" + sessionId + "/chat")
                .delete().build();
        boolean serverCancelled;
        try (Response response = http.newCall(req).execute()) {
            // 204 = cancelled, 404 = no live turn — both are fine, only network errors throw.
            serverCancelled = response.code() == 204;
        } catch (Exception e) {
            throw new RuntimeException("cancelChat failed: " + e.getMessage(), e);
        }
        // Then hard-abort the local SSE stream so the client stops rendering
        // tokens immediately. The chat() method observes the cancel and
        // returns gracefully instead of throwing.
        Call live = activeChatCalls.remove(sessionId);
        if (live != null) live.cancel();
        return serverCancelled;
    }

    @Override
    public WorkingMemory getWorkingMemory(UUID sessionId) {
        Request req = new Request.Builder()
                .url(baseUrl + "/api/sessions/" + sessionId + "/memory")
                .get().build();
        return execute(req, WorkingMemory.class);
    }

    @Override
    public int compressMemory(UUID sessionId) {
        Request req = new Request.Builder()
                .url(baseUrl + "/api/sessions/" + sessionId + "/compress")
                .post(RequestBody.create("", JSON)).build();
        try (Response resp = http.newCall(req).execute()) {
            assertOk(resp);
            JsonNode node = mapper.readTree(resp.body().string());
            return node.path("compressedMessages").asInt(0);
        } catch (Exception e) {
            throw new RuntimeException("Remote call failed: " + e.getMessage(), e);
        }
    }

    @Override
    public void deleteSession(UUID sessionId) {
        Request req = new Request.Builder()
                .url(baseUrl + "/api/sessions/" + sessionId)
                .delete().build();
        try (Response resp = http.newCall(req).execute()) {
            assertOk(resp);
        } catch (Exception e) {
            throw new RuntimeException("Remote call failed: " + e.getMessage(), e);
        }
    }

    @Override
    public int deleteMessages(UUID sessionId, int fromSeq, int toSeq) {
        Request req = new Request.Builder()
                .url(baseUrl + "/api/sessions/" + sessionId + "/messages?fromSeq=" + fromSeq + "&toSeq=" + toSeq)
                .delete().build();
        try (Response resp = http.newCall(req).execute()) {
            assertOk(resp);
            JsonNode node = mapper.readTree(resp.body().string());
            return node.path("deletedMessages").asInt(0);
        } catch (Exception e) {
            throw new RuntimeException("Remote call failed: " + e.getMessage(), e);
        }
    }

    // --- helpers ---

    private <T> T execute(Request req, Class<T> type) {
        try (Response resp = http.newCall(req).execute()) {
            assertOk(resp);
            return mapper.readValue(resp.body().string(), type);
        } catch (Exception e) {
            throw new RuntimeException("Remote call failed: " + e.getMessage(), e);
        }
    }

    private <T> T execute(Request req, TypeReference<T> type) {
        try (Response resp = http.newCall(req).execute()) {
            assertOk(resp);
            return mapper.readValue(resp.body().string(), type);
        } catch (Exception e) {
            throw new RuntimeException("Remote call failed: " + e.getMessage(), e);
        }
    }

    private void assertOk(Response resp) {
        if (!resp.isSuccessful()) {
            throw new RuntimeException("Server returned " + resp.code());
        }
    }

    private String toJson(Object obj) {
        try {
            return mapper.writeValueAsString(obj);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
