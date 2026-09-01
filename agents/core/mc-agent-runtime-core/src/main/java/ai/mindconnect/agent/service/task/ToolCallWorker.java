package ai.mindconnect.agent.service.task;

import ai.mindconnect.agent.domain.AgentDefinition;
import ai.mindconnect.agent.domain.AgentSession;
import ai.mindconnect.agent.domain.StreamEvent;
import ai.mindconnect.agent.memory.port.in.MemoryStrategy;
import ai.mindconnect.agent.memory.port.in.MemoryStrategyFactory;
import ai.mindconnect.agent.port.out.AgentDefinitionRepository;
import ai.mindconnect.agent.port.out.TokenCounter;
import ai.mindconnect.agent.service.AgentSessionService;
import ai.mindconnect.agent.service.InlineAgentTools;
import ai.mindconnect.agent.service.round.ToolCalls;
import ai.mindconnect.agent.service.round.TurnMessage;
import ai.mindconnect.agent.service.stream.SessionChannels;
import ai.mindconnect.agent.service.turn.ToolExecutor;
import ai.mindconnect.agent.tool.ToolRegistry;
import ai.mindconnect.agent.tools.toolsearch.DynamicToolActivations;
import ai.mindconnect.common.DomainException;
import ai.mindconnect.common.LoggingContext;
import ai.mindconnect.common.PageRequest;
import ai.mindconnect.llm.domain.ToolCall;
import ai.mindconnect.message.domain.Message;
import ai.mindconnect.message.domain.MessageType;
import ai.mindconnect.message.port.in.ConversationManager;
import ai.mindconnect.taskqueue.TaskContext;
import ai.mindconnect.taskqueue.TaskOutcome;
import ai.mindconnect.taskqueue.TaskQueue;
import ai.mindconnect.taskqueue.TaskSubmission;
import ai.mindconnect.taskqueue.TaskWorker;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * One tool call as a queue task (concept 16, step 5). Executes the call and
 * <b>writes the {@code TOOL_RESULT} into the conversation itself</b> — that is
 * the whole trick: the suspended parent turn is woken by this task turning
 * terminal, reloads its history, and finds the call closed. Nothing hands a
 * result back; the conversation is the hand-over.
 *
 * <p>The inline delegation tools are one KIND of call this worker runs; their
 * orchestration — spawning, awaiting and resuming sub-agent turns, bubbling
 * approval requests — lives in {@link SubAgentCalls}. This class stays what
 * its name says: execute one call, write its one result.
 *
 * <p>Invariants:
 * <ul>
 *   <li><b>Every dispatched call ends in exactly one TOOL_RESULT.</b> Failure,
 *       cancellation and crash-retry all funnel into the same append; a second
 *       execution that finds the result already written does nothing.</li>
 *   <li><b>The task id is the call:</b> {@code task_tool_<turnId>_<callId>} —
 *       submitting is idempotent (a resumed turn re-dispatching gets the same
 *       task) and the parent derives the ids it suspends on without asking
 *       anyone.</li>
 * </ul>
 */
public final class ToolCallWorker implements TaskWorker {

    private static final Logger log = LoggerFactory.getLogger(ToolCallWorker.class);

    public static final String TYPE = "agent.tool";
    public static final String CALL_ID = "callId";
    public static final String TOOL_NAME = "toolName";
    public static final String ARGUMENTS = "arguments";
    public static final String TURN_ID = "turnId";
    public static final String SESSION_ID = "sessionId";
    public static final String DEPTH = "depth";
    public static final String RUN = "run";

    private final ConversationManager conversationManager;
    private final AgentDefinitionRepository definitionRepository;
    private final AgentSessionService sessionService;
    private final MemoryStrategyFactory memoryStrategyFactory;
    private final ToolRegistry toolRegistry;
    private final DynamicToolActivations dynamicToolActivations;
    private final ToolExecutor toolExecutor;
    private final SessionChannels sessionChannels;
    private final ai.mindconnect.agent.service.approval.ToolApprovalStore approvalStore;
    private final SubAgentCalls subAgents;

    public ToolCallWorker(ConversationManager conversationManager,
                          AgentDefinitionRepository definitionRepository,
                          AgentSessionService sessionService,
                          MemoryStrategyFactory memoryStrategyFactory,
                          ToolRegistry toolRegistry,
                          DynamicToolActivations dynamicToolActivations,
                          ToolExecutor toolExecutor,
                          SessionChannels sessionChannels,
                          ai.mindconnect.agent.service.approval.ToolApprovalStore approvalStore) {
        this.conversationManager = conversationManager;
        this.definitionRepository = definitionRepository;
        this.sessionService = sessionService;
        this.memoryStrategyFactory = memoryStrategyFactory;
        this.toolRegistry = toolRegistry;
        this.dynamicToolActivations = dynamicToolActivations;
        this.toolExecutor = toolExecutor;
        this.sessionChannels = sessionChannels;
        this.approvalStore = approvalStore;
        this.subAgents = new SubAgentCalls(conversationManager, definitionRepository,
                sessionService, memoryStrategyFactory, sessionChannels);
    }

    /** Wires the queue in after construction; must happen before the first task runs. */
    public void attach(TaskQueue queue) {
        subAgents.attach(queue);
    }

    /** The task id of a call — deterministic, so dispatch is idempotent and the parent can derive it. */
    public static String taskIdFor(UUID turnId, String callId) {
        return "task_tool_" + turnId + "_" + callId;
    }

    /** One call as a submission. {@code depth} is the PARENT turn's depth, {@code run} its loop run. */
    public static TaskSubmission submission(UUID turnId, int run, UUID sessionId, int depth,
                                            ToolCalls.Call call) {
        Map<String, Object> payload = new HashMap<>();
        payload.put(CALL_ID, call.callId());
        payload.put(TOOL_NAME, call.name());
        payload.put(ARGUMENTS, call.arguments());
        payload.put(TURN_ID, turnId.toString());
        payload.put(RUN, run);
        payload.put(SESSION_ID, sessionId.toString());
        payload.put(DEPTH, depth);
        // priority = depth + 1: a tool overtakes queued root turns, so the
        // parked parent that waits for it never starves behind fresh work.
        return TaskSubmission.of(TYPE, payload)
                .withPriority(depth + 1)
                .withId(taskIdFor(turnId, call.callId()));
    }

    // ── the call ────────────────────────────────────────────────────────────

    @Override
    @SuppressWarnings("unchecked")
    public TaskOutcome execute(TaskContext ctx) {
        UUID turnId = UUID.fromString(string(ctx, TURN_ID));
        UUID sessionId = UUID.fromString(string(ctx, SESSION_ID));
        String callId = string(ctx, CALL_ID);
        String toolName = string(ctx, TOOL_NAME);
        int depth = ((Number) ctx.task().payload().getOrDefault(DEPTH, 0)).intValue();
        int run = ((Number) ctx.task().payload().getOrDefault(RUN, 0)).intValue();
        Map<String, Object> arguments = ctx.task().payload().get(ARGUMENTS) instanceof Map<?, ?> m
                ? (Map<String, Object>) m : Map.of();

        AgentSession session = sessionService.findSession(sessionId);
        AgentDefinition def = effectiveDefinition(session);

        try (var ignored = LoggingContext.session(session.id(), session.conversationId(), def.name())) {
            // At-least-once made harmless: a retry that finds the result
            // already written has nothing left to do.
            if (resultExists(session.conversationId(), callId)) {
                log.debug("TOOL_RESULT for call {} already written — duplicate execution, skipping", callId);
                return TaskOutcome.done("duplicate");
            }

            MemoryStrategy memoryStrategy = memoryStrategyFactory.create(def);
            TokenCounter tokenCounter = memoryStrategy.resolveTokenCounter(def);
            Consumer<StreamEvent> stream = sessionChannels.publisherFor(session.id(), turnId, run);
            ConversationMessageLog messageLog = new ConversationMessageLog(
                    conversationManager, UUID.randomUUID() /* user sender — see follow-up task */,
                    def.id(), turnId, run, tokenCounter);

            // THE GATE (Claude-style): approval is checked HERE, right before
            // execution — not planned ahead by the round. A gated call is
            // simply a slow tool: this task parks, the turn above stays
            // suspended, and the woken execution re-decides from the
            // notification (Allow/Deny once) or the fresh session rules
            // (Allow for this session — which thereby covers every parked
            // sibling without any sweeping).
            switch (approvalGate(ctx, session, def, callId, toolName, arguments)) {
                case WAIT -> {
                    return TaskOutcome.suspendUntilNotified();
                }
                case DENIED -> {
                    messageLog.append(session.conversationId(),
                            TurnMessage.toolResult(callId, toolName,
                                    "Error: the user did not approve this tool call", true)
                                    .with("approval", "denied"));
                    return TaskOutcome.done("denied");
                }
                case PROCEED -> { }
            }

            long startMs = System.currentTimeMillis();
            String output;
            boolean failed = false;
            if (ctx.cancelRequested()) {
                output = "Tool call cancelled by user (not executed)";
                failed = true;
            } else {
                try {
                    output = InlineAgentTools.RUN_AGENT.equals(toolName)
                            || InlineAgentTools.RUN_AGENTS.equals(toolName)
                            ? subAgents.run(ctx, session, def, turnId, depth, stream, toolName, callId, arguments)
                            : runRegistryTool(session, def, tokenCounter, stream, callId, toolName, arguments);
                    failed = output != null && output.startsWith("Error:");
                } catch (RuntimeException e) {
                    log.warn("Tool '{}' threw: {}", toolName, e.getMessage());
                    output = "Error: " + e.getMessage();
                    failed = true;
                }
            }

            // Cancelled while the tool ran: the cancel FLAG is set before the
            // interrupt reaches the tool's thread, so this check is on the
            // deterministic side of the race — cancelChat's stub owns the
            // call, this execution's output is discarded, never appended.
            if (ctx.cancelRequested()) {
                log.info("Call {} was cancelled while the tool ran — output discarded, the stub owns the call",
                        callId);
                return TaskOutcome.done("CANCELLED");
            }

            // A stub or a duplicate execution may have closed this call while
            // the tool ran — a second result for the same callId must never
            // be written.
            if (resultExists(session.conversationId(), callId)) {
                log.info("Result for call {} appeared while the tool ran (cancel stub) — discarding output",
                        callId);
                return TaskOutcome.done("superseded");
            }

            // THE hand-over: the result becomes conversation truth; the queue
            // waking the suspended parent is merely the doorbell. Capped
            // FIRST — an oversized dump never reaches conversation, window
            // or DB (per-tool maxResultChars, plus the runtime safety cap).
            output = capResult(def, toolName, output);
            messageLog.append(session.conversationId(),
                    TurnMessage.toolResult(callId, toolName, output, failed)
                            .with("durationMs", System.currentTimeMillis() - startMs));
            return TaskOutcome.done(failed ? "failed" : "ok");
        }
    }

    // ── the approval gate ───────────────────────────────────────────────────

    private enum Gate { PROCEED, WAIT, DENIED }

    private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper();

    /**
     * Decides, in order: an explicit answer from the human (delivered as a
     * task notification — the only way "Allow once"/"Deny" can work without
     * a standing rule), then the standing rules (the agent's flag minus the
     * session chain's approvals — "allow for this session" at the root covers
     * every sub-agent), and only then parking: one store entry per open
     * question (idempotent), one live card push, {@code WAIT}.
     */
    private Gate approvalGate(TaskContext ctx, AgentSession session, AgentDefinition def,
                              String callId, String toolName, Map<String, Object> arguments) {
        for (var notification : ctx.notifications()) {
            Boolean decision = ai.mindconnect.agent.service.approval.ApprovalNotifications
                    .decision(notification);
            if (decision != null) {
                approvalStore.delete(callId);
                return decision ? Gate.PROCEED : Gate.DENIED;
            }
        }
        boolean flagged = def.tools().stream()
                .anyMatch(t -> toolName.equals(t.name()) && t.enabled() && t.needsApproval());
        if (!flagged || sessionService.isToolApproved(session.id(), toolName)) {
            approvalStore.delete(callId);   // no stale card outlives the decision
            return Gate.PROCEED;
        }
        AgentSession root = sessionService.rootSession(session.id());
        var entry = new ai.mindconnect.agent.service.approval.ToolApproval(
                ctx.task().id(), callId, toolName, approvalContent(toolName, arguments),
                session.id(), root.id(), ctx.task().id(), java.time.Instant.now());
        if (approvalStore.saveIfAbsent(entry)) {
            log.info("Tool '{}' (call {}) waits at the approval gate — card registered for root session {}",
                    toolName, callId, root.id());
            publishApprovalCard(root, entry, arguments);
        }
        return Gate.WAIT;
    }

    /** The card's payload — same {@code {"name","arguments"}} shape the UI has always parsed. */
    private static String approvalContent(String toolName, Map<String, Object> arguments) {
        try {
            return MAPPER.writeValueAsString(Map.of(
                    "name", toolName, "arguments", arguments == null ? Map.of() : arguments));
        } catch (Exception e) {
            return "{\"name\":\"" + toolName + "\",\"arguments\":{}}";
        }
    }

    /**
     * The live mirror of the registered card, pushed on the ROOT turn's
     * channel — the suspended chat stream shows the card at once instead of
     * waiting for a reload. Best-effort: the store still renders it.
     */
    private void publishApprovalCard(AgentSession root,
                                     ai.mindconnect.agent.service.approval.ToolApproval entry,
                                     Map<String, Object> arguments) {
        try {
            var rootHistory = conversationManager.loadCompleteHistory(root.conversationId());
            UUID rootTurnId = rootHistory.currentTurnId().orElse(null);
            if (rootTurnId == null) return;
            sessionChannels.publisherFor(root.id(), rootTurnId, rootHistory.currentRun())
                    .accept(new StreamEvent.ApprovalRequested(
                    entry.requestId(), entry.callId(), entry.toolName(),
                    arguments == null ? Map.of() : arguments,
                    entry.originSessionId(), entry.toolTaskId()));
        } catch (Exception e) {
            log.warn("Live approval card push failed (the store still renders it on reload): {}",
                    e.getMessage());
        }
    }

    private String runRegistryTool(AgentSession session, AgentDefinition def,
                                   TokenCounter tokenCounter, Consumer<StreamEvent> stream,
                                   String callId, String toolName, Map<String, Object> arguments) {
        SessionTools tools = new SessionTools(toolRegistry, dynamicToolActivations, def, session);
        ToolExecutor.Context toolContext = new ToolExecutor.Context(
                stream, session.conversationId(), def.id(), tokenCounter,
                session.namespace(), session.userId(), session.id());
        return toolExecutor.execute(new ToolCall(callId, toolName, arguments),
                tools.liveTool(toolName), toolContext).resultText();
    }

    /**
     * The runtime-wide safety cap: no tool result grows beyond this, ever,
     * whatever the agent's config says. Far above anything a model reads —
     * it exists to keep megabyte dumps out of the DB and the UI.
     */
    static final int HARD_RESULT_CAP_CHARS = 100_000;

    /**
     * Cuts the output at the tool's configured {@code maxResultChars} (when
     * set and positive), always at {@link #HARD_RESULT_CAP_CHARS}. The cut
     * is visible in the text so the model (and the human) know it happened.
     * Deliberately lossy — the compression path is the lossless one; this is
     * the opt-in source cap for tools known to over-produce.
     */
    private static String capResult(AgentDefinition def, String toolName, String output) {
        if (output == null) return null;
        Integer configured = def.tools().stream()
                .filter(t -> toolName.equals(t.name()))
                .map(ai.mindconnect.agent.tool.AgentTool::maxResultChars)
                .filter(max -> max != null && max > 0)
                .findFirst().orElse(null);
        int limit = configured != null ? Math.min(configured, HARD_RESULT_CAP_CHARS)
                : HARD_RESULT_CAP_CHARS;
        if (output.length() <= limit) return output;
        return output.substring(0, limit)
                + "\n… [output truncated after " + limit + " characters]";
    }

    private boolean resultExists(UUID conversationId, String callId) {
        return conversationManager.loadHistory(conversationId, new PageRequest(0, Integer.MAX_VALUE))
                .stream()
                .anyMatch(m -> m.type() == MessageType.TOOL_RESULT
                        && callId.equals(m.metadata().get("callId")));
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private static String string(TaskContext ctx, String key) {
        Object value = ctx.task().payload().get(key);
        if (value == null) {
            throw new IllegalArgumentException("Tool task is missing payload key '" + key + "'");
        }
        return value.toString();
    }


    /**
     * The definition this session runs — its own inline agent, a registry
     * agent with this chat's overrides, or (older sessions) the definition
     * behind {@code agentDefinitionId}.
     */
    private AgentDefinition effectiveDefinition(AgentSession session) {
        return new ai.mindconnect.agent.service.SessionAgentResolver(definitionRepository).resolve(session);
    }

}
