package ai.mindconnect.agent.service.task;

import ai.mindconnect.agent.domain.AgentDefinition;
import ai.mindconnect.agent.domain.AgentSession;
import ai.mindconnect.agent.domain.StreamEvent;
import ai.mindconnect.agent.domain.TraceContext;
import ai.mindconnect.agent.memory.domain.WorkingMemory;
import ai.mindconnect.agent.memory.port.in.MemoryStrategy;
import ai.mindconnect.agent.memory.port.in.MemoryStrategyFactory;
import ai.mindconnect.agent.memory.port.out.WorkingMemoryRepository;
import ai.mindconnect.agent.port.in.AgentTaskRunner;
import ai.mindconnect.agent.port.out.AgentDefinitionRepository;
import ai.mindconnect.agent.port.out.LlmCallTraceRepository;
import ai.mindconnect.agent.port.out.PromptRenderer;
import ai.mindconnect.agent.port.out.TokenCounter;
import ai.mindconnect.agent.service.AgentSessionService;
import ai.mindconnect.agent.service.round.AgentLoop;
import ai.mindconnect.agent.service.round.AgentRound;
import ai.mindconnect.agent.service.round.LlmAnswer;
import ai.mindconnect.agent.service.round.TurnMessage;
import ai.mindconnect.agent.service.round.TurnOutcome;
import ai.mindconnect.agent.service.stream.SessionChannels;
import ai.mindconnect.agent.service.turn.WorkingMemoryBuilder;
import ai.mindconnect.agent.tool.ToolRegistry;
import ai.mindconnect.agent.tools.toolsearch.DynamicToolActivations;
import ai.mindconnect.common.AuthenticationInfo;
import ai.mindconnect.common.Cancellation;
import ai.mindconnect.common.DomainException;
import ai.mindconnect.common.LoggingContext;
import ai.mindconnect.llm.port.in.LlmChat;
import ai.mindconnect.message.domain.Message;
import ai.mindconnect.message.domain.MessageType;
import ai.mindconnect.message.domain.ParticipantType;
import ai.mindconnect.message.port.in.ConversationManager;
import ai.mindconnect.taskqueue.TaskContext;
import ai.mindconnect.taskqueue.TaskOutcome;
import ai.mindconnect.taskqueue.TaskSubmission;
import ai.mindconnect.taskqueue.TaskWorker;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * One chat turn as a queue task (concept 16, step 4) — the bridge between the
 * queue and {@link AgentLoop}, and the ONE place the turn's collaborators are
 * assembled: message log, LLM provider, toolset, sub-agent dispatch, reviewer
 * seam, memory hooks. Everything the old ChatTurnContext scattered lives in
 * this assembly for exactly one execution.
 *
 * <p><b>The user's message is not in the payload.</b> The submitter appends it
 * to the conversation first ({@link #appendUserMessage}), so the payload is
 * ids only and a repeated execution never appends the question twice.
 *
 * <p>Tool calls are child tasks ({@code agent.tool}); while they run, this
 * task SUSPENDS on them — no thread, no slot. Sub-agents hang off the tool
 * task that spawned them (turn → tool → sub-turn), so the cancel cascade
 * reaches everything and {@code run_agents} parallelism comes from the queue.
 */
public final class AgentTurnWorker implements TaskWorker {

    private static final Logger log = LoggerFactory.getLogger(AgentTurnWorker.class);

    public static final String TYPE = "agent.turn";
    public static final String TURN_ID = "turnId";
    public static final String SESSION_ID = "sessionId";
    public static final String DEPTH = "depth";
    public static final String RUN = "run";
    public static final String PARENT_TURN_ID = "parentTurnId";

    /** Hard cap on tool-calling rounds within a single turn (as before). */
    static final int MAX_ROUNDS = 10;
    /** Hard cap on sub-agent recursion. Prevents runaway delegation chains. */
    public static final int MAX_DEPTH = 5;

    private final ConversationManager conversationManager;
    private final AgentDefinitionRepository definitionRepository;
    private final AgentSessionService sessionService;
    private final MemoryStrategyFactory memoryStrategyFactory;
    private final PromptRenderer promptRenderer;
    private final ToolRegistry toolRegistry;
    private final DynamicToolActivations dynamicToolActivations;
    private final LlmChat llmChat;
    /** Nullable — no repository, no tracing. */
    private final LlmCallTraceRepository traceRepository;
    private final SessionChannels sessionChannels;
    private final AgentTaskRunner agentTaskRunner;
    private final WorkingMemoryRepository workingMemoryRepository;

    public AgentTurnWorker(ConversationManager conversationManager,
                           AgentDefinitionRepository definitionRepository,
                           AgentSessionService sessionService,
                           MemoryStrategyFactory memoryStrategyFactory,
                           PromptRenderer promptRenderer,
                           ToolRegistry toolRegistry,
                           DynamicToolActivations dynamicToolActivations,
                           LlmChat llmChat,
                           LlmCallTraceRepository traceRepository,
                           SessionChannels sessionChannels,
                           AgentTaskRunner agentTaskRunner,
                           WorkingMemoryRepository workingMemoryRepository) {
        this.conversationManager = conversationManager;
        this.definitionRepository = definitionRepository;
        this.sessionService = sessionService;
        this.memoryStrategyFactory = memoryStrategyFactory;
        this.promptRenderer = promptRenderer;
        this.toolRegistry = toolRegistry;
        this.dynamicToolActivations = dynamicToolActivations;
        this.llmChat = llmChat;
        this.traceRepository = traceRepository;
        this.sessionChannels = sessionChannels;
        this.agentTaskRunner = agentTaskRunner;
        this.workingMemoryRepository = workingMemoryRepository;
    }

    /**
     * The task id of one turn EXECUTION, derived from the turn's id and its
     * loop run: {@code task_turn_<turnId>} for the first execution,
     * {@code ..._r<run>} for approval resumes. The turnId stays the LOGICAL
     * turn (stable across resumes, as it always was) — the run counts the
     * executions, and both are readable off the conversation's messages
     * ({@code Message.turnId}/{@code Message.run}), so the running task is
     * findable from domain state alone and submitting stays idempotent.
     */
    public static String taskIdFor(UUID turnId, int run) {
        return run == 0 ? "task_turn_" + turnId : "task_turn_" + turnId + "_r" + run;
    }

    /** The submission for one turn execution: ids only (concept 11's payload rule). */
    public static TaskSubmission submission(UUID turnId, int run, UUID sessionId,
                                            int depth, UUID parentTurnId) {
        Map<String, Object> payload = new HashMap<>();
        payload.put(TURN_ID, turnId.toString());
        payload.put(RUN, run);
        payload.put(SESSION_ID, sessionId.toString());
        payload.put(DEPTH, depth);
        if (parentTurnId != null) payload.put(PARENT_TURN_ID, parentTurnId.toString());
        return TaskSubmission.of(TYPE, payload).withPriority(depth).withId(taskIdFor(turnId, run));
    }

    /**
     * Appends the user's message to the conversation — BEFORE the task is
     * submitted, by whoever starts the turn (the chat facade for roots, this
     * worker for sub-agents). Returns the persisted message.
     */
    public static Message appendUserMessage(ConversationManager conversationManager,
                                            UUID conversationId, String text,
                                            UUID turnId, TokenCounter tokenCounter) {
        Message persisted = conversationManager.addMessageToConversation(
                conversationId, UUID.randomUUID() /* user sender — see follow-up task */,
                ParticipantType.USER, MessageType.CHAT, text, turnId, 0, Map.of());
        conversationManager.updateTokenCount(conversationId, persisted.id(),
                tokenCounter.countText(text));
        return persisted;
    }

    // ── the turn ────────────────────────────────────────────────────────────

    @Override
    public TaskOutcome execute(TaskContext ctx) {
        UUID turnId = uuid(ctx, TURN_ID);
        UUID sessionId = uuid(ctx, SESSION_ID);
        int run = ((Number) ctx.task().payload().getOrDefault(RUN, 0)).intValue();
        int depth = ((Number) ctx.task().payload().getOrDefault(DEPTH, 0)).intValue();
        UUID parentTurnId = optionalUuid(ctx, PARENT_TURN_ID);
        if (depth > MAX_DEPTH) {
            throw new IllegalStateException(
                    "Sub-agent depth limit (" + MAX_DEPTH + ") exceeded at depth " + depth);
        }

        AgentSession session = sessionService.findSession(sessionId);
        AgentDefinition def = definitionRepository.findById(session.agentDefinitionId())
                .orElseThrow(() -> DomainException.notFound(
                        "AgentDefinition", session.agentDefinitionId().toString()));

        try (var ignored = LoggingContext.session(session.id(), session.conversationId(), def.name())) {
            return runTurn(ctx, turnId, run, parentTurnId, depth, session, def);
        }
    }

    private TaskOutcome runTurn(TaskContext ctx, UUID turnId, int run, UUID parentTurnId, int depth,
                                AgentSession session, AgentDefinition def) {
        Cancellation cancellation = new Cancellation();
        ctx.onCancel(cancellation::cancel);

        // Resolved per execution BY ID — this is what survives a suspension
        // and lets a resumed turn stream seamlessly on (concept 12/16).
        Consumer<StreamEvent> stream = sessionChannels.publisherFor(session.id(), turnId, run);

        MemoryStrategy memoryStrategy = memoryStrategyFactory.create(def);
        TokenCounter tokenCounter = memoryStrategy.resolveTokenCounter(def);
        AuthenticationInfo auth = AuthenticationInfo.of(session.userId(), session.namespace());
        UUID conversationId = session.conversationId();

        // THE load of this execution (concept 16: read once): everything
        // downstream — fold, window, user message — reads this instance,
        // kept current by the message log's appends.
        var history = conversationManager.loadCompleteHistory(conversationId);

        // Tool-result compression at execution entry — the window is not
        // rendered yet, so the marks (withCompressed; the original stays in
        // content) take effect for this very round. The strategy's rules
        // keep unread and recent results full. Marks change stored rows, so
        // only an actual mark forces the one extra reload.
        try {
            int marked = memoryStrategy.compressEligibleToolResults(def, session, auth, history.messages());
            if (marked > 0) {
                history = conversationManager.loadCompleteHistory(conversationId);
            }
        } catch (Exception e) {
            log.warn("Tool-result compression failed (continuing uncompressed): {}", e.getMessage());
        }

        Optional<Message> userMessage = history.currentTurn()
                .map(ai.mindconnect.message.domain.ChatTurn::userMessage);

        ConversationMessageLog messageLog = new ConversationMessageLog(
                conversationManager, history, UUID.randomUUID() /* user sender — see follow-up task */,
                def.id(), turnId, run, tokenCounter);

        SessionTools tools = new SessionTools(toolRegistry, dynamicToolActivations, def, session);
        QueuedAgentRoundToolExecutor executor = new QueuedAgentRoundToolExecutor(
                ctx, conversationManager, sessionService, def, session, turnId, run, conversationId, depth);

        LlmChatProvider llm = new LlmChatProvider(llmChat, def, session, memoryStrategy,
                promptRenderer, stream, traceRepository,
                new TraceContext(conversationId, session.id(), turnId, parentTurnId, depth, def.name()));

        // Turn-level policy as advisors around each round: the reviewer chain
        // rewrites an ANSWERED outcome before persistence.
        ReviewerAdvisor reviewer = new ReviewerAdvisor(agentTaskRunner, conversationManager,
                def, session, userMessage.orElse(null), stream);
        AgentLoop loop = new AgentLoop(new AgentRound(llm, tools, executor), messageLog,
                MAX_ROUNDS, message -> { }, List.of(reviewer));

        int roundsSoFar = roundsSoFar(ctx);
        TurnOutcome outcome = loop.run(turnId.toString(), conversationId, session.id(),
                cancellation, roundsSoFar);
        ctx.updateState(Map.of("rounds", outcome.rounds(), "status", outcome.status().name()));

        if (outcome.waitsForTools()) {
            // The whole point of step 5: give the thread back. The tool tasks'
            // ids are derivable from the callIds; when the last one turns
            // terminal the queue requeues this task, the next execution
            // reloads the history and finds the calls closed.
            return TaskOutcome.suspendUntil(outcome.waitingFor().stream()
                    .map(callId -> ToolCallWorker.taskIdFor(turnId, callId))
                    .collect(java.util.stream.Collectors.toSet()));
        }
        if (outcome.status() == TurnOutcome.Status.CANCELLED) {
            return TaskOutcome.done("CANCELLED");
        }
        String finalText = outcome.text();
        if (outcome.status() == TurnOutcome.Status.INCOMPLETE
                && outcome.incompleteReason() == TurnOutcome.IncompleteReason.MAX_OUTPUT_TOKENS) {
            // The model was cut off mid-answer (finish=LENGTH). The partial
            // text is already persisted; without this note the table "just
            // stops" and nobody knows why — including the model next turn.
            messageLog.append(conversationId, TurnMessage.assistant(
                    "⚠️ *The answer was cut off at the model's output limit — the context is "
                            + "likely nearly full. Ask me to continue, or raise the model's "
                            + "context/output limits (or enable tool-result compression).*"));
        }
        if (outcome.status() == TurnOutcome.Status.INCOMPLETE
                && outcome.incompleteReason() == TurnOutcome.IncompleteReason.MAX_ROUNDS) {
            // The old loop's last-round rule, kept: out of rounds means "answer
            // now, without tools" — not "end with no answer at all".
            finalText = forceFinalAnswer(llm, messageLog, turnId, conversationId, session,
                    cancellation, reviewer);
        }

        stream.accept(new StreamEvent.Done());
        afterTurn(memoryStrategy, def, session, auth);
        saveWorkingMemorySnapshot(memoryStrategy, def, session, auth);
        return TaskOutcome.done(finalText);
    }

    // ── post-loop pieces ────────────────────────────────────────────────────

    /** Out of rounds: one last model call without tools — answer now, reviewed like any answer. */
    private String forceFinalAnswer(LlmChatProvider llm, ConversationMessageLog messageLog,
                                    UUID turnId, UUID conversationId, AgentSession session,
                                    Cancellation cancellation, ReviewerAdvisor reviewer) {
        try {
            LlmAnswer answer = llm.ask(turnId.toString(), session.id(),
                    messageLog.load(conversationId), List.of(), cancellation);
            String text = answer.messages().stream()
                    .filter(m -> m.type() == MessageType.CHAT)
                    .map(TurnMessage::content)
                    .findFirst().orElse("");
            String reviewed = reviewer.review(text);
            messageLog.append(conversationId, TurnMessage.assistant(reviewed));
            return reviewed;
        } catch (RuntimeException e) {
            log.warn("Forced final answer after MAX_ROUNDS failed: {}", e.getMessage());
            return "";
        }
    }

    private void afterTurn(MemoryStrategy memoryStrategy, AgentDefinition def,
                           AgentSession session, AuthenticationInfo auth) {
        try {
            memoryStrategy.onAfterTurn(def, session, auth);
        } catch (Exception e) {
            log.warn("Memory strategy onAfterTurn failed: {}", e.getMessage());
        }
    }

    private void saveWorkingMemorySnapshot(MemoryStrategy memoryStrategy, AgentDefinition def,
                                           AgentSession session, AuthenticationInfo auth) {
        try {
            WorkingMemory stats = WorkingMemoryBuilder.build(
                    promptRenderer, memoryStrategy, def, session, auth);
            workingMemoryRepository.save(session.id(), auth, stats);
        } catch (Exception e) {
            log.warn("Failed to save working memory for session {}: {}", session.id(), e.getMessage());
        }
    }

    // ── small helpers ───────────────────────────────────────────────────────

    private static int roundsSoFar(TaskContext ctx) {
        Object rounds = ctx.state().get("rounds");
        return rounds instanceof Number n ? n.intValue() : 0;
    }

    private static UUID uuid(TaskContext ctx, String key) {
        Object value = ctx.task().payload().get(key);
        if (value == null) {
            throw new IllegalArgumentException("Turn task is missing payload key '" + key + "'");
        }
        return UUID.fromString(value.toString());
    }

    private static UUID optionalUuid(TaskContext ctx, String key) {
        Object value = ctx.task().payload().get(key);
        return value == null ? null : UUID.fromString(value.toString());
    }

    private static String string(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
