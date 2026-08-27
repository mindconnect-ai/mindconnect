package ai.mindconnect.agent.protocol.openai;

import ai.mindconnect.agent.protocol.Conversation;
import ai.mindconnect.agent.protocol.Response;
import ai.mindconnect.agent.protocol.ResponseStatus;
import ai.mindconnect.agent.protocol.Session;
import ai.mindconnect.agent.protocol.StoredFile;
import ai.mindconnect.agent.protocol.Usage;
import ai.mindconnect.agent.protocol.api.AgentResponses;
import ai.mindconnect.agent.protocol.api.Conversations;
import ai.mindconnect.agent.protocol.api.Files;
import ai.mindconnect.agent.protocol.api.ResponseRequest;
import ai.mindconnect.agent.protocol.api.Sessions;
import ai.mindconnect.agent.protocol.api.SubscribeRequest;
import ai.mindconnect.agent.protocol.api.ToolDefinition;
import ai.mindconnect.agent.protocol.api.Subscription;
import ai.mindconnect.agent.protocol.event.ResponseEvent;
import ai.mindconnect.agent.protocol.item.ConversationItem;
import ai.mindconnect.agent.protocol.item.ConversationItemRecord;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Backend adapter: the whole protocol surface ({@link Sessions},
 * {@link AgentResponses}, {@link Conversations}) implemented against the
 * real OpenAI Responses + Conversations API. No MindConnect runtime involved
 * — agents are {@link PseudoAgent}s registered locally.
 *
 * <pre>
 * var backend = new OpenAiResponsesBackend(apiKey)
 *         .register(PseudoAgent.of("assistant", "gpt-5-mini", "Be brief.")
 *                 .withHostedTool("web_search"));
 * Session s = backend.open("demo", "assistant");
 * Response r = backend.create(ResponseRequest.text(s.id(), "What happened today?"));
 * </pre>
 *
 * <p>The three surfaces are exposed by composition ({@link #sessions()},
 * {@link #responses()}, {@link #conversations()}) — they cannot live on one
 * class because all three interfaces declare {@code get(UUID)} with different
 * return types. Convenience delegates for the common calls are provided.
 *
 * <p>Limitations (v1): {@code subscribe} needs a {@code background} response
 * (OpenAI only re-streams those); sub-agent fields stay empty; approval items
 * are not supported as input. Ids are OpenAI's own ({@code resp_…},
 * {@code conv_…}) — the protocol's String ids carry them verbatim.
 */
public final class OpenAiResponsesBackend {

    private record SessionState(Session session, PseudoAgent agent) { }

    /** What create() knew — so a later get() can map identically. */
    private record ResponseMeta(String sessionId, String agentName, Set<String> functionToolNames) { }

    private final OpenAiHttp http;
    private final Map<String, PseudoAgent> agents = new ConcurrentHashMap<>();
    private final Map<String, SessionState> sessions = new ConcurrentHashMap<>();
    private final Map<String, ResponseMeta> responseMeta = new ConcurrentHashMap<>();
    private final Map<String, Response> logicalSnapshots = new ConcurrentHashMap<>();

    /** Name of the injected delegation tool (agent-as-tool, concept 8 option a). */
    private static final String RUN_AGENT = "run_agent";
    private static final int MAX_DELEGATION_ROUNDS = 8;
    private static final int MAX_DEPTH = 3;

    public OpenAiResponsesBackend(String apiKey) {
        this(apiKey, "https://api.openai.com/v1");
    }

    public OpenAiResponsesBackend(String apiKey, String baseUrl) {
        this.http = new OpenAiHttp(baseUrl, apiKey);
    }

    /** Registers a pseudo agent; fluent so setup reads like a builder. */
    public OpenAiResponsesBackend register(PseudoAgent agent) {
        agents.put(agent.name(), agent);
        return this;
    }

    // ── The three protocol surfaces, by composition ─────────────────────────

    public Sessions sessions() { return sessionsApi; }

    public AgentResponses responses() { return responsesApi; }

    public Conversations conversations() { return conversationsApi; }

    public Files files() { return filesApi; }

    // Convenience delegates for the common calls:

    public Session openSessionForAgent(String namespace, String agentName) { return sessionsApi.open(namespace, agentName); }

    public Response create(ResponseRequest request) { return responsesApi.create(request); }

    private final Sessions sessionsApi = new Sessions() {

        @Override
        public Session open(String namespace, String agentName) {
            PseudoAgent agent = requireAgent(agentName);
            JsonNode conv = http.post("/conversations", Map.of());
            return bind(namespace, conv.path("id").asText(), agent);
        }

        @Override
        public Session openOn(String conversationId, String agentName) {
            return bind("default", conversationId, requireAgent(agentName));
        }

        @Override
        public Optional<Session> get(String sessionId) {
            return Optional.ofNullable(sessions.get(sessionId)).map(SessionState::session);
        }
    };

    private final AgentResponses responsesApi = new AgentResponses() {

    @Override
    public Response create(ResponseRequest request) {
        SessionState state = requireSession(request.sessionId());
        if (!state.agent().agentTools().isEmpty() && request.background()) {
            throw new OpenAiBackendException(
                    "background together with agent tools is not supported yet", null);
        }
        return createLogical(state, request, 0);
    }

    @Override
    public Optional<Response> get(String responseId) {
        Response logical = logicalSnapshots.get(responseId);
        if (logical != null) return Optional.of(logical);
        JsonNode n = http.get("/responses/" + responseId);
        ResponseMeta meta = responseMeta.getOrDefault(responseId,
                new ResponseMeta(null, null, Set.of()));
        return Optional.of(OpenAiMapper.response(n, meta.sessionId(), meta.agentName(),
                meta.functionToolNames()));
    }

    @Override
    public boolean cancel(String responseId) {
        JsonNode n = http.post("/responses/" + responseId + "/cancel", Map.of());
        return OpenAiMapper.status(n.path("status").asText()) == ResponseStatus.CANCELLED;
    }

    @Override
    public Subscription subscribe(SubscribeRequest request, Consumer<ResponseEvent> consumer) {
        StringBuilder path = new StringBuilder("/responses/").append(request.responseId())
                .append("?stream=true");
        if (request.afterSeq() > 0 && request.afterSeq() != Long.MAX_VALUE) {
            path.append("&starting_after=").append(request.afterSeq());
        }
        // includeChildren: no sub-agents on this backend — nothing to merge.
        AutoCloseable handle = http.stream(path.toString(), node ->
                OpenAiMapper.event(node, request.responseId()).ifPresent(consumer));
        return () -> {
            try {
                handle.close();
            } catch (Exception ignored) {
            }
        };
    }
    };

    private final Conversations conversationsApi = new Conversations() {

    @Override
    public Conversation create(String namespace) {
        JsonNode n = http.post("/conversations", Map.of());
        return new Conversation(n.path("id").asText(), namespace,
                Instant.ofEpochSecond(n.path("created_at").asLong()));
    }

    @Override
    public Optional<Conversation> get(String conversationId) {
        JsonNode n = http.get("/conversations/" + conversationId);
        return Optional.of(new Conversation(conversationId, "default",
                Instant.ofEpochSecond(n.path("created_at").asLong())));
    }

    @Override
    public ConversationItemRecord append(String conversationId, ConversationItem item) {
        JsonNode n = http.post("/conversations/" + conversationId + "/items",
                Map.of("items", List.of(OpenAiMapper.inputItemJson(item))));
        JsonNode first = n.path("data").path(0);
        return new ConversationItemRecord(first.path("id").asText("item"), 0, item);
    }

    @Override
    public List<ConversationItemRecord> items(String conversationId, long afterSeq, int limit) {
        JsonNode n = http.get("/conversations/" + conversationId
                + "/items?order=asc&limit=" + Math.min(Math.max(limit, 1), 100));
        List<ConversationItemRecord> result = new ArrayList<>();
        long seq = 0;
        for (JsonNode itemNode : n.path("data")) {
            seq++;
            if (seq <= afterSeq) continue;
            result.add(new ConversationItemRecord(itemNode.path("id").asText("item-" + seq), seq,
                    OpenAiMapper.outputItem(itemNode)));
        }
        return result;
    }

    };

    // ── Delegation engine (agent-as-tool) ───────────────────────────────────

    /**
     * Runs one LOGICAL response: possibly several OpenAI responses on the
     * same conversation, looped until no open {@code run_agent} call remains.
     * The logical id is the first round's OpenAI id; items of all rounds
     * aggregate in order; usage is summed. Children run as OpenAI background
     * responses polled to completion, so their ids are live — {@code get}
     * and {@code cancel} on a running child work (cancel is best-effort:
     * it reaches the child's current round).
     */
    private Response createLogical(SessionState state, ResponseRequest request, int depth) {
        PseudoAgent agent = state.agent();
        boolean delegating = !agent.agentTools().isEmpty();

        Set<String> functionNames = new HashSet<>();
        agent.tools().forEach(t -> functionNames.add(t.name()));
        request.clientTools().forEach(t -> functionNames.add(t.name()));
        if (delegating) functionNames.add(RUN_AGENT);

        List<ConversationItem> input = request.input();
        String logicalId = null;
        List<ConversationItemRecord> aggregate = new ArrayList<>();
        Usage usage = Usage.ZERO;
        Response last = null;

        for (int round = 0; round < MAX_DELEGATION_ROUNDS; round++) {
            JsonNode res = postRound(state, agent, input, request.clientTools(),
                    request.background(), depth > 0, functionNames);
            last = OpenAiMapper.response(res, state.session().id(), agent.name(), functionNames);
            if (logicalId == null) logicalId = last.id();
            usage = usage.plus(last.usage());
            for (ConversationItemRecord e : last.output()) {
                aggregate.add(new ConversationItemRecord(e.id(), aggregate.size() + 1, e.item()));
            }
            if (request.background() && !delegating) {
                return logicalResponse(logicalId, state, agent, last, aggregate, usage);
            }
            List<ConversationItem.FunctionCall> delegations = last.openFunctionCalls().stream()
                    .filter(c -> RUN_AGENT.equals(c.name())).toList();
            if (delegations.isEmpty()) break;
            List<ConversationItem> outputs = new ArrayList<>();
            for (ConversationItem.FunctionCall call : delegations) {
                ConversationItem.FunctionCallOutput out = delegate(state, call, aggregate, depth);
                outputs.add(out);
                aggregate.add(new ConversationItemRecord("out_" + call.callId(), aggregate.size() + 1, out));
            }
            input = outputs;
        }
        Response logical = logicalResponse(logicalId, state, agent, last, aggregate, usage);
        logicalSnapshots.put(logicalId, logical);
        return logical;
    }

    /** One POST /responses; registers the id, optionally polls a background run to its end. */
    private JsonNode postRound(SessionState state, PseudoAgent agent, List<ConversationItem> input,
                               List<ToolDefinition> clientTools, boolean bodyBackground,
                               boolean poll, Set<String> functionNames) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", agent.model());
        if (agent.instructions() != null) body.put("instructions", agent.instructions());
        body.put("conversation", state.session().conversationId());
        body.put("input", input.stream().map(OpenAiMapper::inputItemJson).toList());
        List<Map<String, Object>> tools = new ArrayList<>(agent.hostedTools());
        agent.tools().forEach(t -> tools.add(OpenAiMapper.functionToolJson(t)));
        clientTools.forEach(t -> tools.add(OpenAiMapper.functionToolJson(t)));
        if (!agent.agentTools().isEmpty()) tools.add(OpenAiMapper.functionToolJson(runAgentTool(agent)));
        if (!tools.isEmpty()) body.put("tools", tools);
        if (agent.toolChoice() != null) body.put("tool_choice", agent.toolChoice());
        body.put("store", true);
        if (bodyBackground || poll) body.put("background", true);

        JsonNode res = http.post("/responses", body);
        responseMeta.putIfAbsent(res.path("id").asText(),
                new ResponseMeta(state.session().id(), agent.name(), functionNames));
        return poll ? awaitTerminal(res.path("id").asText()) : res;
    }

    /** Executes one run_agent call: spawns the child, rewrites the item, returns the output. */
    private ConversationItem.FunctionCallOutput delegate(SessionState parentState, ConversationItem.FunctionCall call,
                                             List<ConversationItemRecord> aggregate, int depth) {
        String targetName = String.valueOf(call.arguments().get("name"));
        String message = String.valueOf(call.arguments().getOrDefault("message", ""));
        if (depth + 1 >= MAX_DEPTH) {
            return new ConversationItem.FunctionCallOutput(call.callId(),
                    "Error: sub-agent depth limit (" + MAX_DEPTH + ") reached", true);
        }
        if (!parentState.agent().agentTools().contains(targetName) || agents.get(targetName) == null) {
            return new ConversationItem.FunctionCallOutput(call.callId(), "Error: no delegable agent named '"
                    + targetName + "'. Available: "
                    + String.join(", ", parentState.agent().agentTools()), true);
        }
        Session childSession = sessionsApi.open(parentState.session().namespace(), targetName);
        try {
            Response child = createLogical(sessions.get(childSession.id()),
                    ResponseRequest.text(childSession.id(), message), depth + 1);
            rewriteToAgentCall(aggregate, call, child.id(), targetName, message);
            boolean failed = child.status() != ResponseStatus.COMPLETED;
            return new ConversationItem.FunctionCallOutput(call.callId(),
                    failed ? "Error: sub-agent ended " + child.status() : child.outputText(), failed);
        } catch (OpenAiBackendException e) {
            return new ConversationItem.FunctionCallOutput(call.callId(), "Error: " + e.getMessage(), true);
        }
    }

    /** The protocol view: the run_agent FunctionCall becomes an AgentCall with the child's id. */
    private void rewriteToAgentCall(List<ConversationItemRecord> aggregate, ConversationItem.FunctionCall call,
                                    String childResponseId, String agentName, String message) {
        for (int i = 0; i < aggregate.size(); i++) {
            ConversationItemRecord e = aggregate.get(i);
            if (e.item() instanceof ConversationItem.FunctionCall fc && fc.callId().equals(call.callId())) {
                aggregate.set(i, new ConversationItemRecord(e.id(), e.seq(),
                        new ConversationItem.AgentCall(call.callId(), agentName, message, childResponseId)));
                return;
            }
        }
    }

    private static ToolDefinition runAgentTool(PseudoAgent agent) {
        return new ToolDefinition(RUN_AGENT,
                "Delegate a task to a specialized sub-agent and receive its answer. "
                        + "Available agents: " + String.join(", ", agent.agentTools()),
                Map.of("type", "object",
                        "properties", Map.of(
                                "name", Map.of("type", "string",
                                        "description", "name of the sub-agent to call"),
                                "message", Map.of("type", "string",
                                        "description", "the task for the sub-agent")),
                        "required", List.of("name", "message")));
    }

    private JsonNode awaitTerminal(String responseId) {
        while (true) {
            JsonNode n = http.get("/responses/" + responseId);
            if (OpenAiMapper.status(n.path("status").asText()).terminal()) return n;
            try {
                Thread.sleep(700);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new OpenAiBackendException("Interrupted while waiting for sub-agent", e);
            }
        }
    }

    private Response logicalResponse(String logicalId, SessionState state, PseudoAgent agent,
                                     Response last, List<ConversationItemRecord> aggregate, Usage usage) {
        return new Response(logicalId, state.session().conversationId(), state.session().id(),
                agent.name(), last.status(), last.incompleteReason(), null, null,
                List.copyOf(aggregate), usage, last.error(), last.metadata(),
                last.createdAt(), last.completedAt());
    }

    private final Files filesApi = new Files() {

        @Override
        public StoredFile upload(String filename, String mediaType, byte[] content) {
            JsonNode n = http.postMultipart("/files", Map.of("purpose", "user_data"),
                    "file", filename, mediaType, content);
            return new StoredFile(n.path("id").asText(), filename, mediaType,
                    n.path("bytes").asLong(content.length));
        }

        @Override
        public Optional<StoredFile> get(String fileId) {
            JsonNode n = http.get("/files/" + fileId);
            return Optional.of(new StoredFile(n.path("id").asText(),
                    n.path("filename").asText(), null, n.path("bytes").asLong(0)));
        }
    };

    // ── helpers ─────────────────────────────────────────────────────────────

    private Session bind(String namespace, String conversationId, PseudoAgent agent) {
        Session session = new Session("sess_" + UUID.randomUUID(), namespace,
                conversationId, agent.name(), Instant.now());
        sessions.put(session.id(), new SessionState(session, agent));
        return session;
    }

    private PseudoAgent requireAgent(String name) {
        PseudoAgent agent = agents.get(name);
        if (agent == null) {
            throw new OpenAiBackendException("No pseudo agent registered under '" + name
                    + "' — call register(PseudoAgent.of(...)) first", null);
        }
        return agent;
    }

    private SessionState requireSession(String sessionId) {
        SessionState state = sessions.get(sessionId);
        if (state == null) {
            throw new OpenAiBackendException("Unknown session " + sessionId, null);
        }
        return state;
    }
}
