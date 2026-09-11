package ai.mindconnect.cli.agentclient;

import ai.mindconnect.agent.UserId;

import ai.mindconnect.agent.SessionId;

import ai.mindconnect.agent.AgentId;

import ai.mindconnect.agent.runtime.domain.AgentDefinition;
import ai.mindconnect.agent.runtime.domain.AgentSession;
import ai.mindconnect.agent.runtime.memory.domain.WorkingMemory;
import ai.mindconnect.agent.runtime.domain.StreamEvent;
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
    private final Map<SessionId, Call> activeChatCalls = new ConcurrentHashMap<>();

    public RemoteAgentClient(String baseUrl, OkHttpClient http, ObjectMapper mapper) {
        this.baseUrl = baseUrl.replaceAll("/$", "");
        this.http = http;
        this.mapper = mapper;
    }

    // CLI does not expose agent CRUD — create/update happens via the
    // admin UI / REST API. RemoteAgentClient therefore only implements
    // the read/list/chat/memory surface of AgentClient.

    @Override
    public Optional<AgentDefinition> findAgent(AgentId agentId) {
        Request req = new Request.Builder()
                .url(baseUrl + "/api/agents/" + agentId.value())
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
    public List<AgentDefinition> listAgents() {
        Request req = new Request.Builder()
                .url(baseUrl + "/api/agents")
                .get().build();
        return execute(req, new TypeReference<>() {
        });
    }

    // --- RunSessionUseCase ---

    /**
     * The server opens the session for the user it authenticated, so
     * {@code userId} — the owner in local mode — is not sent.
     */
    @Override
    public AgentSession startSession(AgentId agentDefinitionId, UserId userId) {
        String body = toJson(Map.of("agentId", agentDefinitionId.value()));
        Request req = new Request.Builder()
                .url(baseUrl + "/api/sessions")
                .post(RequestBody.create(body, JSON)).build();
        return execute(req, AgentSession.class);
    }

    /** The authenticated user's sessions; {@code userId} is not sent, as for {@link #startSession}. */
    @Override
    public List<AgentSession> listSessions(AgentId agentDefinitionId, UserId userId) {
        String url = baseUrl + "/api/sessions?agentId=" + agentDefinitionId.value();
        Request req = new Request.Builder().url(url).get().build();
        return execute(req, new TypeReference<>() {
        });
    }

    @Override
    public List<Message> loadHistory(SessionId sessionId) {
        Request req = new Request.Builder()
                .url(baseUrl + "/api/sessions/" + sessionId.value() + "/history")
                .get().build();
        return execute(req, new TypeReference<>() {
        });
    }

    @Override
    public String chat(SessionId sessionId, String userMessage, Consumer<StreamEvent> eventHandler) {
        Request req = new Request.Builder()
                .url(baseUrl + "/api/sessions/" + sessionId.value() + "/chat")
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
            // A server too old to send the frame sends no counts at all,
            // which reads as untracked rather than as a measured zero.
            case "turn_usage" -> new StreamEvent.TurnUsage(
                    node.path("inputTokens").asLong(0), node.path("outputTokens").asLong(0));
            case "done" -> new StreamEvent.Done();
            case "sub_agent_started" -> {
                UUID taskId = UUID.fromString(node.path("taskId").asText());
                String agentName = node.path("agentName").asText("");
                int depth = node.path("depth").asInt(0);
                SessionId subSessionId = node.path("subSessionId").isMissingNode()
                        || node.path("subSessionId").isNull()
                        ? null : SessionId.of(node.path("subSessionId").asText());
                String input = node.path("text").isMissingNode() || node.path("text").isNull()
                        ? null : node.path("text").asText();
                yield new StreamEvent.SubAgentStarted(taskId, agentName, depth, subSessionId, input);
            }
            case "sub_agent_done" -> {
                UUID taskId = UUID.fromString(node.path("taskId").asText());
                String agentName = node.path("agentName").asText("");
                SessionId subSessionId = SessionId.of(node.path("subSessionId").asText());
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
                        origin == null || origin.isBlank() ? null : SessionId.of(origin),
                        node.path("error").asText(null));
            }
            default -> null;
        };
    }

    @Override
    public boolean cancelChat(SessionId sessionId) {
        // Send the cooperative cancel to the server first so the turn loop exits
        // and stops doing work as soon as it can.
        Request req = new Request.Builder()
                .url(baseUrl + "/api/sessions/" + sessionId.value() + "/chat")
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
    public WorkingMemory getWorkingMemory(SessionId sessionId) {
        Request req = new Request.Builder()
                .url(baseUrl + "/api/sessions/" + sessionId.value() + "/memory")
                .get().build();
        return execute(req, WorkingMemory.class);
    }

    @Override
    public int compressMemory(SessionId sessionId) {
        Request req = new Request.Builder()
                .url(baseUrl + "/api/sessions/" + sessionId.value() + "/compress")
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
    public void deleteSession(SessionId sessionId) {
        Request req = new Request.Builder()
                .url(baseUrl + "/api/sessions/" + sessionId.value())
                .delete().build();
        try (Response resp = http.newCall(req).execute()) {
            assertOk(resp);
        } catch (Exception e) {
            throw new RuntimeException("Remote call failed: " + e.getMessage(), e);
        }
    }

    @Override
    public int deleteMessages(SessionId sessionId, int fromSeq, int toSeq) {
        Request req = new Request.Builder()
                .url(baseUrl + "/api/sessions/" + sessionId.value() + "/messages?fromSeq=" + fromSeq + "&toSeq=" + toSeq)
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
