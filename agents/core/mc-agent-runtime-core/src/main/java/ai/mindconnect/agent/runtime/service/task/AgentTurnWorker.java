package ai.mindconnect.agent.runtime.service.task;

import ai.mindconnect.message.domain.ConversationId;
import ai.mindconnect.message.domain.ChatTurnId;
import ai.mindconnect.agent.SessionId;
import ai.mindconnect.agent.runtime.domain.AgentDefinition;
import ai.mindconnect.agent.runtime.domain.AgentSession;
import ai.mindconnect.agent.runtime.domain.StreamEvent;
import ai.mindconnect.agent.runtime.domain.TraceContext;
import ai.mindconnect.agent.runtime.memory.domain.WorkingMemory;
import ai.mindconnect.agent.runtime.memory.port.in.MemoryStrategy;
import ai.mindconnect.agent.runtime.memory.port.in.MemoryStrategyFactory;
import ai.mindconnect.agent.runtime.memory.port.out.WorkingMemoryRepository;
import ai.mindconnect.agent.runtime.port.in.AgentTaskRunner;
import ai.mindconnect.agent.runtime.port.out.AgentDefinitionRepository;
import ai.mindconnect.agent.runtime.port.out.LlmCallTraceRepository;
import ai.mindconnect.agent.runtime.port.out.PromptRenderer;
import ai.mindconnect.agent.runtime.port.out.TokenCounter;
import ai.mindconnect.agent.runtime.service.AgentSessionService;
import ai.mindconnect.agent.runtime.service.SessionAgentResolver;
import ai.mindconnect.agent.runtime.service.round.AgentLoop;
import ai.mindconnect.agent.runtime.service.round.AgentRound;
import ai.mindconnect.agent.runtime.service.round.LlmAnswer;
import ai.mindconnect.agent.runtime.service.round.TurnMessage;
import ai.mindconnect.agent.runtime.service.round.TurnOutcome;
import ai.mindconnect.agent.runtime.service.round.Usage;
import ai.mindconnect.agent.runtime.service.stream.SessionChannels;
import ai.mindconnect.agent.runtime.service.prompt.InstructionFiles;
import ai.mindconnect.agent.runtime.skill.SkillCatalog;
import ai.mindconnect.agent.runtime.service.turn.WorkingMemoryBuilder;
import ai.mindconnect.agent.tool.ToolRegistry;
import ai.mindconnect.agent.runtime.tools.toolsearch.DynamicToolActivations;
import ai.mindconnect.agent.AuthenticationInfo;
import ai.mindconnect.agent.UserId;
import ai.mindconnect.common.Cancellation;
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
    private final InstructionFiles instructions;
    private final SkillCatalog skills;

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
                           WorkingMemoryRepository workingMemoryRepository,
                           InstructionFiles instructions) {
        this(conversationManager, definitionRepository, sessionService, memoryStrategyFactory,
                promptRenderer, toolRegistry, dynamicToolActivations, llmChat, traceRepository,
                sessionChannels, agentTaskRunner, workingMemoryRepository, instructions,
                SkillCatalog.none());
    }

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
                           WorkingMemoryRepository workingMemoryRepository,
                           InstructionFiles instructions,
                           SkillCatalog skills) {
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
        this.instructions = instructions;
        this.skills = skills == null ? SkillCatalog.none() : skills;
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
    public static String taskIdFor(ChatTurnId turnId, int run) {
        return run == 0 ? "task_turn_" + turnId.value() : "task_turn_" + turnId.value() + "_r" + run;
    }

    /** The submission for one turn execution: ids only (concept 11's payload rule). */
    public static TaskSubmission submission(ChatTurnId turnId, int run, SessionId sessionId,
                                            int depth, ChatTurnId parentTurnId) {
        Map<String, Object> payload = new HashMap<>();
        payload.put(TURN_ID, turnId.value());
        payload.put(RUN, run);
        payload.put(SESSION_ID, sessionId.value());
        payload.put(DEPTH, depth);
        if (parentTurnId != null) payload.put(PARENT_TURN_ID, parentTurnId.value());
        return TaskSubmission.of(TYPE, payload).withPriority(depth).withId(taskIdFor(turnId, run));
    }

    /**
     * Appends the user's message to the conversation — BEFORE the task is
     * submitted, by whoever starts the turn (the chat facade for roots, this
     * worker for sub-agents). Returns the persisted message.
     */
    public static Message appendUserMessage(ConversationManager conversationManager,
                                            ConversationId conversationId, UserId sender, String text,
                                            ChatTurnId turnId, TokenCounter tokenCounter) {
        return appendUserMessage(conversationManager, conversationId, sender, text, turnId, tokenCounter, Map.of());
    }

    /** Same, with metadata on the message — e.g. the attachments it announces. */
    public static Message appendUserMessage(ConversationManager conversationManager,
                                            ConversationId conversationId, UserId sender, String text,
                                            ChatTurnId turnId, TokenCounter tokenCounter,
                                            Map<String, Object> metadata) {
        Message persisted = conversationManager.addMessageToConversation(
                conversationId, sender.value(),
                ParticipantType.USER, MessageType.CHAT, text, turnId, 0, metadata);
        conversationManager.updateTokenCount(persisted.conversationId(), persisted.id(), tokenCounter.countText(text));
        return persisted;
    }

    /**
     * Same, for a message made of content parts — the text plus the images
     * and documents sent with it. The token count is the text's; what a
     * media part costs is the provider's business.
     */
    public static Message appendUserMessage(ConversationManager conversationManager,
                                            ConversationId conversationId, UserId sender,
                                            java.util.List<ai.mindconnect.message.domain.ContentPart> parts,
                                            ChatTurnId turnId, TokenCounter tokenCounter,
                                            Map<String, Object> metadata) {
        Message persisted = conversationManager.addMessageToConversation(
                conversationId, sender.value(),
                ParticipantType.USER, MessageType.CHAT, parts, turnId, 0, metadata);
        conversationManager.updateTokenCount(persisted.conversationId(), persisted.id(), tokenCounter.countText(persisted.content()));
        return persisted;
    }

    // ── the turn ────────────────────────────────────────────────────────────

    @Override
    public TaskOutcome execute(TaskContext ctx) {
        ChatTurnId turnId = ChatTurnId.of(string(ctx, TURN_ID));
        SessionId sessionId = SessionId.of(string(ctx, SESSION_ID));
        int run = ((Number) ctx.task().payload().getOrDefault(RUN, 0)).intValue();
        int depth = ((Number) ctx.task().payload().getOrDefault(DEPTH, 0)).intValue();
        String parentTurn = optionalString(ctx, PARENT_TURN_ID);
        ChatTurnId parentTurnId = parentTurn == null ? null : ChatTurnId.of(parentTurn);
        if (depth > MAX_DEPTH) {
            throw new IllegalStateException(
                    "Sub-agent depth limit (" + MAX_DEPTH + ") exceeded at depth " + depth);
        }

        AgentSession session = sessionService.findSession(sessionId);
        AgentDefinition def = effectiveDefinition(session);

        try (var ignored = LoggingContext.session(session.id(), session.conversationId(), def.name())) {
            return runTurn(ctx, turnId, run, parentTurnId, depth, session, def);
        }
    }

    private TaskOutcome runTurn(TaskContext ctx, ChatTurnId turnId, int run, ChatTurnId parentTurnId, int depth,
                                AgentSession session, AgentDefinition def) {
        Cancellation cancellation = new Cancellation();
        ctx.onCancel(cancellation::cancel);

        // Resolved per execution BY ID — this is what survives a suspension
        // and lets a resumed turn stream seamlessly on (concept 12/16).
        Consumer<StreamEvent> stream = sessionChannels.publisherFor(session.id(), turnId, run);

        MemoryStrategy memoryStrategy = memoryStrategyFactory.create(def);
        TokenCounter tokenCounter = memoryStrategy.resolveTokenCounter(def);
        AuthenticationInfo auth = AuthenticationInfo.of(session.userId());
        ConversationId conversationId = session.conversationId();

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
                conversationManager, history, session.userId().value(), def.id(), turnId, run, tokenCounter);

        // A sub-agent's tools see the chat that started the chain: the user's uploads are there.
        SessionTools tools = new SessionTools(toolRegistry, dynamicToolActivations, def, session,
                session.parentSessionId() == null ? session.id() : sessionService.rootSession(session.id()).id());
        QueuedAgentRoundToolExecutor executor = new QueuedAgentRoundToolExecutor(
                ctx, conversationManager, sessionService, def, session, turnId, run, conversationId, depth);

        LlmChatProvider llm = new LlmChatProvider(llmChat, def, session, memoryStrategy,
                promptRenderer, stream, traceRepository,
                new TraceContext(conversationId, session.id(), turnId, parentTurnId, depth, def.name()),
                instructions, skills);

        // Turn-level policy as advisors around each round: the reviewer chain
        // rewrites an ANSWERED outcome before persistence.
        ReviewerAdvisor reviewer = new ReviewerAdvisor(agentTaskRunner, conversationManager,
                def, session, userMessage.orElse(null), stream);
        AgentLoop loop = new AgentLoop(new AgentRound(llm, tools, executor), messageLog,
                MAX_ROUNDS, message -> { }, List.of(reviewer));

        int roundsSoFar = roundsSoFar(ctx);
        TurnOutcome outcome = loop.run(turnId.value(), conversationId, session.id(),
                cancellation, roundsSoFar, usageSoFar(ctx));
        // The usage rides in the task state for the same reason the round count
        // does: a turn that suspends on a tool resumes as a fresh execution,
        // and what the earlier legs spent lives nowhere else.
        Usage spent = outcome.usage();
        recordUsage(ctx, outcome, spent);
        // Reported here rather than only with Done: a turn that is cancelled,
        // or that suspends on a tool, never reaches Done, and its cost is
        // worth knowing exactly then.
        stream.accept(new StreamEvent.TurnUsage(spent.inputTokens(), spent.outputTokens()));

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
            ForcedAnswer forced = forceFinalAnswer(llm, messageLog, turnId, conversationId,
                    session, cancellation, reviewer);
            finalText = forced.text();
            // That was a real model call. Leaving it out would make the turn
            // that needed it look cheaper than the ones that did not.
            spent = spent.plus(forced.usage());
            recordUsage(ctx, outcome, spent);
            stream.accept(new StreamEvent.TurnUsage(spent.inputTokens(), spent.outputTokens()));
        }

        stream.accept(new StreamEvent.Done());
        afterTurn(memoryStrategy, def, session, auth);
        saveWorkingMemorySnapshot(memoryStrategy, def, session, auth);
        return TaskOutcome.done(finalText);
    }

    // ── post-loop pieces ────────────────────────────────────────────────────

    /** Out of rounds: one last model call without tools — answer now, reviewed like any answer. */
    /** The forced answer and what that extra model call cost. */
    private record ForcedAnswer(String text, Usage usage) { }

    private ForcedAnswer forceFinalAnswer(LlmChatProvider llm, ConversationMessageLog messageLog,
                                          ChatTurnId turnId, ConversationId conversationId, AgentSession session,
                                          Cancellation cancellation, ReviewerAdvisor reviewer) {
        try {
            LlmAnswer answer = llm.ask(turnId.value(), session.id(),
                    messageLog.load(conversationId), List.of(), cancellation);
            String text = answer.messages().stream()
                    .filter(m -> m.type() == MessageType.CHAT)
                    .map(TurnMessage::content)
                    .findFirst().orElse("");
            String reviewed = reviewer.review(text);
            messageLog.append(conversationId, TurnMessage.assistant(reviewed));
            return new ForcedAnswer(reviewed, answer.usage());
        } catch (RuntimeException e) {
            log.warn("Forced final answer after MAX_ROUNDS failed: {}", e.getMessage());
            // The call may still have been billed, but nothing here knows how
            // much; claiming zero is the only honest option left.
            return new ForcedAnswer("", Usage.ZERO);
        }
    }

    /**
     * The running total in the task state, for the same reason the round count
     * is there: a turn that suspends on a tool resumes as a fresh execution,
     * and what the earlier legs spent lives nowhere else.
     */
    private static void recordUsage(TaskContext ctx, TurnOutcome outcome, Usage spent) {
        ctx.updateState(Map.of("rounds", outcome.rounds(), "status", outcome.status().name(),
                "inputTokens", spent.inputTokens(),
                "outputTokens", spent.outputTokens()));
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
                    promptRenderer, memoryStrategy, def, session, auth, instructions, skills);
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

    /** What earlier executions of this turn already spent; zero on the first. */
    private static Usage usageSoFar(TaskContext ctx) {
        return new Usage(longState(ctx, "inputTokens"), longState(ctx, "outputTokens"));
    }

    private static long longState(TaskContext ctx, String key) {
        Object value = ctx.state().get(key);
        return value instanceof Number n ? n.longValue() : 0L;
    }

    private static String string(TaskContext ctx, String key) {
        Object value = ctx.task().payload().get(key);
        if (value == null) {
            throw new IllegalArgumentException("Turn task is missing payload key '" + key + "'");
        }
        return value.toString();
    }

    private static String optionalString(TaskContext ctx, String key) {
        Object value = ctx.task().payload().get(key);
        return value == null ? null : value.toString();
    }

    private static String string(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    /**
     * The definition this session runs — its own inline agent, a registry
     * agent with this chat's overrides, or (older sessions) the definition
     * behind {@code agentDefinitionId}.
     */
    private AgentDefinition effectiveDefinition(AgentSession session) {
        return new SessionAgentResolver(definitionRepository).resolve(session);
    }

}
