package ai.mindconnect.agent.domain;

import ai.mindconnect.common.Namespace;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.UUID;

public record AgentSession(
        UUID id,
        UUID agentDefinitionId,
        Namespace namespace,
        String userId,
        UUID conversationId,
        String title,
        SessionStatus status,
        Instant startedAt,
        Instant completedAt,
        /**
         * If this session was spawned by a {@code run_agent} call from
         * another session, the id of that parent session. {@code null}
         * for top-level (user-initiated) sessions.
         */
        UUID parentSessionId,
        /**
         * If this session was spawned by a {@code run_agent} call, the
         * id of the parent's chat-turn that triggered the spawn.
         * {@code null} for top-level sessions. Lets the trace UI link
         * back to the exact roundtrip in the parent's history.
         */
        UUID parentTurnId,
        /**
         * If this session was spawned by a {@code run_agent} call, the
         * {@code tool_call_id} of the parent's TOOL_CALL message that
         * spawned it. {@code null} for top-level sessions.
         *
         * <p>Lets the UI nest a sub-agent's tool-calls under the exact
         * {@code run_agent} card that started them — and works for
         * parallel sub-agents because each spawn has its own
         * {@code tool_call_id} (no ambiguity even when multiple
         * sub-agents run concurrently from the same parent turn).
         */
        String parentToolCallId,
        /**
         * Tools activated for this session at runtime beyond the agent's
         * configured list — by tool_search, or by attaching a file to the
         * chat (which activates vector_search). Persisted with the session,
         * so activations survive restarts and die with the session.
         */
        java.util.List<String> activatedTools,
        /**
         * Names of files attached to this conversation (chat uploads). The
         * system-prompt renderer announces them to the LLM together with the
         * hint to use vector_search; maintained by the session file service.
         */
        java.util.List<String> attachedFiles,
        /**
         * Tool NAMES the user approved for the rest of this session ("allow
         * for this session" on an approval card). Deliberately session
         * CONFIGURATION rather than something derived from the messages: the
         * ToolCalls fold is episode-local by design, but a standing approval
         * must reach across episodes — and as an explicit field the UI can
         * show and revoke it. The APPROVAL_RESPONSE message still records the
         * decision moment for audit. Per tool name, never per parameter set.
         */
        java.util.Set<String> approvedTools,
        /**
         * The agents this session runs, exactly one of them {@code main}.
         * Empty for every session written before session agents existed —
         * then {@link #agentDefinitionId} alone decides, as it always did.
         *
         * <p>A list because a session will eventually host several agents
         * talking to each other; today anything but one entry is a bug.
         */
        java.util.List<ai.mindconnect.agent.domain.session.SessionAgent> sessionAgents
) implements ai.mindconnect.agent.domain.view.AgentSessionHeader {
    public AgentSession {
        if (activatedTools == null) activatedTools = java.util.List.of();
        if (attachedFiles == null) attachedFiles = java.util.List.of();
        if (approvedTools == null) approvedTools = java.util.Set.of();
        if (sessionAgents == null) sessionAgents = java.util.List.of();
        // "Exactly one main" is the invariant the whole resolution path rests
        // on: without a main agent, mainAgent() is empty and the runtime falls
        // back to agentDefinitionId — which for an inline agent resolves to
        // nothing, so every turn would fail with a confusing notFound.
        if (!sessionAgents.isEmpty()) {
            long mains = sessionAgents.stream()
                    .filter(ai.mindconnect.agent.domain.session.SessionAgent::main).count();
            if (mains != 1) {
                throw new IllegalArgumentException(
                        "A session needs exactly one main agent, found " + mains);
            }
        }
    }

    /** Pre-activatedTools constructor: nothing activated. */
    public AgentSession(UUID id, UUID agentDefinitionId, Namespace namespace, String userId,
                        UUID conversationId, String title, SessionStatus status,
                        Instant startedAt, Instant completedAt, UUID parentSessionId,
                        UUID parentTurnId, String parentToolCallId) {
        this(id, agentDefinitionId, namespace, userId, conversationId, title, status,
                startedAt, completedAt, parentSessionId, parentTurnId, parentToolCallId,
                java.util.List.of(), java.util.List.of(), java.util.Set.of(), java.util.List.of());
    }

    /** Pre-approvedTools constructor: nothing approved yet. */
    public AgentSession(UUID id, UUID agentDefinitionId, Namespace namespace, String userId,
                        UUID conversationId, String title, SessionStatus status,
                        Instant startedAt, Instant completedAt, UUID parentSessionId,
                        UUID parentTurnId, String parentToolCallId,
                        java.util.List<String> activatedTools, java.util.List<String> attachedFiles) {
        this(id, agentDefinitionId, namespace, userId, conversationId, title, status,
                startedAt, completedAt, parentSessionId, parentTurnId, parentToolCallId,
                activatedTools, attachedFiles, java.util.Set.of(), java.util.List.of());
    }

    /** This session plus one tool approved for the rest of it. */
    public AgentSession withApprovedTool(String toolName) {
        java.util.LinkedHashSet<String> merged = new java.util.LinkedHashSet<>(approvedTools);
        merged.add(toolName);
        return new AgentSession(id, agentDefinitionId, namespace, userId, conversationId,
                title, status, startedAt, completedAt, parentSessionId, parentTurnId,
                parentToolCallId, activatedTools, attachedFiles, java.util.Set.copyOf(merged), sessionAgents);
    }

    /** This session plus one activated tool (no-op if already active). */
    public AgentSession withActivatedTools(java.util.Collection<String> names) {
        java.util.LinkedHashSet<String> merged = new java.util.LinkedHashSet<>(activatedTools);
        merged.addAll(names);
        return new AgentSession(id, agentDefinitionId, namespace, userId, conversationId,
                title, status, startedAt, completedAt, parentSessionId, parentTurnId,
                parentToolCallId, java.util.List.copyOf(merged), attachedFiles, approvedTools, sessionAgents);
    }

    /** This session plus attached file names (deduplicated, order kept). */
    public AgentSession withAttachedFiles(java.util.Collection<String> names) {
        java.util.LinkedHashSet<String> merged = new java.util.LinkedHashSet<>(attachedFiles);
        merged.addAll(names);
        return new AgentSession(id, agentDefinitionId, namespace, userId, conversationId,
                title, status, startedAt, completedAt, parentSessionId, parentTurnId,
                parentToolCallId, activatedTools, java.util.List.copyOf(merged), approvedTools, sessionAgents);
    }

    /** This session without the given attached file name. */
    public AgentSession withoutAttachedFile(String name) {
        return new AgentSession(id, agentDefinitionId, namespace, userId, conversationId,
                title, status, startedAt, completedAt, parentSessionId, parentTurnId,
                parentToolCallId, activatedTools,
                attachedFiles.stream().filter(f -> !f.equals(name)).toList(), approvedTools, sessionAgents);
    }

    /** Jackson deserialisation — unknown legacy fields (compressionWatermark, lastCompressedAt) are silently ignored. */
    @JsonCreator
    public static AgentSession fromJson(
            @JsonProperty("id")                UUID id,
            @JsonProperty("agentDefinitionId") UUID agentDefinitionId,
            @JsonProperty("namespace")         Namespace namespace,
            @JsonProperty("userId")            String userId,
            @JsonProperty("conversationId")    UUID conversationId,
            @JsonProperty("title")             String title,
            @JsonProperty("status")            SessionStatus status,
            @JsonProperty("startedAt")         Instant startedAt,
            @JsonProperty("completedAt")       Instant completedAt,
            @JsonProperty("parentSessionId")   UUID parentSessionId,
            @JsonProperty("parentTurnId")      UUID parentTurnId,
            @JsonProperty("parentToolCallId")  String parentToolCallId,
            @JsonProperty("activatedTools")    java.util.List<String> activatedTools,
            @JsonProperty("attachedFiles")     java.util.List<String> attachedFiles,
            @JsonProperty("approvedTools")     java.util.Set<String> approvedTools,
            @JsonProperty("sessionAgents")     java.util.List<ai.mindconnect.agent.domain.session.SessionAgent> sessionAgents) {
        return new AgentSession(id, agentDefinitionId, namespace, userId, conversationId,
                title, status, startedAt, completedAt, parentSessionId, parentTurnId,
                parentToolCallId, activatedTools, attachedFiles, approvedTools, sessionAgents);
    }

    /**
     * The agent that drives the turn, or empty for a session written before
     * session agents existed (then {@link #agentDefinitionId} is the whole
     * story, exactly as before).
     */
    public java.util.Optional<ai.mindconnect.agent.domain.session.SessionAgent> mainAgent() {
        return sessionAgents.stream()
                .filter(ai.mindconnect.agent.domain.session.SessionAgent::main)
                .findFirst();
    }

    /** This session with its agents replaced. */
    public AgentSession withSessionAgents(
            java.util.List<ai.mindconnect.agent.domain.session.SessionAgent> agents) {
        return new AgentSession(id, agentDefinitionId, namespace, userId, conversationId,
                title, status, startedAt, completedAt, parentSessionId, parentTurnId,
                parentToolCallId, activatedTools, attachedFiles, approvedTools, agents);
    }

    /** Top-level session — no parent linkage. */
    public static AgentSession start(UUID agentDefinitionId, Namespace namespace,
                                     String userId, UUID conversationId) {
        return startSubAgent(agentDefinitionId, namespace, userId, conversationId, null, null, null);
    }

    /**
     * Sub-agent session spawned by {@code run_agent}. Parent linkage lets
     * the trace and admin UIs walk the call tree without scanning every
     * conversation directory on disk. {@code parentToolCallId} ties this
     * session back to the specific TOOL_CALL message in the parent's
     * history that spawned it — keeps parallel sub-agents addressable.
     */
    public static AgentSession startSubAgent(UUID agentDefinitionId, Namespace namespace,
                                              String userId, UUID conversationId,
                                              UUID parentSessionId, UUID parentTurnId,
                                              String parentToolCallId) {
        return new AgentSession(UUID.randomUUID(), agentDefinitionId, namespace,
                userId, conversationId, null, SessionStatus.ACTIVE, Instant.now(), null,
                parentSessionId, parentTurnId, parentToolCallId);
    }

    public AgentSession withTitle(String title) {
        return new AgentSession(id, agentDefinitionId, namespace, userId,
                conversationId, title, status, startedAt, completedAt,
                parentSessionId, parentTurnId, parentToolCallId, activatedTools, attachedFiles, approvedTools, sessionAgents);
    }

    public AgentSession complete() {
        return new AgentSession(id, agentDefinitionId, namespace, userId,
                conversationId, title, SessionStatus.COMPLETED, startedAt, Instant.now(),
                parentSessionId, parentTurnId, parentToolCallId, activatedTools, attachedFiles, approvedTools, sessionAgents);
    }

    public AgentSession error() {
        return new AgentSession(id, agentDefinitionId, namespace, userId,
                conversationId, title, SessionStatus.ERROR, startedAt, Instant.now(),
                parentSessionId, parentTurnId, parentToolCallId, activatedTools, attachedFiles, approvedTools, sessionAgents);
    }
}
