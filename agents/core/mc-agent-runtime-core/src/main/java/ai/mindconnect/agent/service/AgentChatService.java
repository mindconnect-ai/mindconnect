package ai.mindconnect.agent.service;

import ai.mindconnect.agent.domain.AgentDefinition;
import ai.mindconnect.agent.domain.AgentSession;
import ai.mindconnect.message.domain.Message;
import ai.mindconnect.agent.service.prompt.AttachmentNotice;
import ai.mindconnect.agent.domain.StreamEvent;
import ai.mindconnect.agent.memory.domain.WorkingMemory;
import ai.mindconnect.agent.memory.port.in.MemoryStrategy;
import ai.mindconnect.agent.memory.port.in.MemoryStrategyFactory;
import ai.mindconnect.agent.memory.port.out.WorkingMemoryRepository;
import ai.mindconnect.agent.port.in.AgentTaskRunner;
import ai.mindconnect.agent.port.in.ChatTurnHandle;
import ai.mindconnect.agent.port.out.AgentDefinitionRepository;
import ai.mindconnect.agent.port.out.PromptRenderer;
import ai.mindconnect.agent.port.out.TokenCounter;
import ai.mindconnect.agent.service.approval.ToolApproval;
import ai.mindconnect.agent.service.approval.ToolApprovalStore;
import ai.mindconnect.agent.service.stream.SessionChannels;
import ai.mindconnect.agent.service.stream.SessionEvent;
import ai.mindconnect.channel.Channel;
import ai.mindconnect.channel.Subscription;
import ai.mindconnect.agent.service.round.TurnMessage;
import ai.mindconnect.agent.service.task.AgentTurnWorker;
import ai.mindconnect.agent.service.turn.LocalChatTurnHandle;
import ai.mindconnect.agent.service.turn.WorkingMemoryBuilder;
import ai.mindconnect.common.AuthenticationInfo;
import ai.mindconnect.common.DomainException;
import ai.mindconnect.common.PageRequest;
import ai.mindconnect.llm.domain.ToolDefinition;
import ai.mindconnect.message.port.in.ConversationManager;
import ai.mindconnect.taskqueue.TaskNotification;
import ai.mindconnect.taskqueue.TaskQueue;
import ai.mindconnect.taskqueue.TaskRecord;
import ai.mindconnect.taskqueue.TaskStatus;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;

/**
 * The chat facade over the task queue (concept 16): append the user's message
 * to the conversation, submit an {@code agent.turn} task, hand back a handle.
 * Everything that IS the turn — the loop, tools, sub-agents, reviewers,
 * memory hooks — lives in {@link AgentTurnWorker}; everything that observes it
 * streams over the turn's channel.
 *
 * <p>Ordering matters and is the whole trick: the message is persisted BEFORE
 * the task exists, so the payload is ids only and a repeated execution never
 * appends the question twice. Cancellation is {@code queue.cancel}, which
 * cascades over the task tree — sub-agents die with their parent without any
 * bookkeeping here.
 */
public class AgentChatService {

    private static final Logger log = LoggerFactory.getLogger(AgentChatService.class);

    /** Upper bound on one turn, sub-agents included — a safety net, not a target. */
    private static final Duration TURN_TIMEOUT = Duration.ofHours(3);

    private final AgentSessionService sessionService;
    private final AgentDefinitionRepository definitionRepository;
    private final ConversationManager conversationManager;
    private final MemoryStrategyFactory memoryStrategyFactory;
    private final WorkingMemoryRepository workingMemoryRepository;
    private final PromptRenderer promptRenderer;
    private final AgentTaskRunner agentTaskRunner;
    private final SessionChannels sessionChannels;
    private final TaskQueue queue;
    private final ToolApprovalStore approvalStore;
    private final ExecutorService turnExecutor;

    public AgentChatService(AgentSessionService sessionService,
                            AgentDefinitionRepository definitionRepository,
                            ConversationManager conversationManager,
                            MemoryStrategyFactory memoryStrategyFactory,
                            WorkingMemoryRepository workingMemoryRepository,
                            PromptRenderer promptRenderer,
                            AgentTaskRunner agentTaskRunner,
                            SessionChannels sessionChannels,
                            TaskQueue queue,
                            ToolApprovalStore approvalStore,
                            ExecutorService turnExecutor) {
        this.sessionService = sessionService;
        this.definitionRepository = definitionRepository;
        this.conversationManager = conversationManager;
        this.memoryStrategyFactory = memoryStrategyFactory;
        this.workingMemoryRepository = workingMemoryRepository;
        this.promptRenderer = promptRenderer;
        this.agentTaskRunner = agentTaskRunner;
        this.sessionChannels = sessionChannels;
        this.queue = queue;
        this.approvalStore = approvalStore;
        this.turnExecutor = turnExecutor;
    }

    /**
     * The tool definitions handled <em>inline</em> by the runtime rather than
     * resolved through the registry — {@code run_agent} and {@code run_agents}.
     * Kept on this facade because the admin UIs read them here.
     */
    public static List<ToolDefinition> inlineToolDefinitions() {
        return InlineAgentTools.definitions();
    }

    // ── Chat: submit + cancel ──────────────────────────────────────────────

    /**
     * Starts a chat turn: user message into the conversation, task onto the
     * queue, the caller's handler onto the turn's channel. The returned
     * handle's future resolves with the final answer once the task is
     * terminal (title generation included, matching the old behaviour).
     */
    public ChatTurnHandle submitChat(UUID sessionId, String userMessage,
                                     Consumer<StreamEvent> eventHandler) {
        AgentSession session = sessionService.findSession(sessionId);
        AgentDefinition def = effectiveDefinition(session);

        boolean isFirstMessage = conversationManager
                .loadHistory(session.conversationId(), new PageRequest(0, 1)).isEmpty();

        UUID turnId = UUID.randomUUID();

        // A new turn can only start when the previous one is over — any open
        // approval cards of that turn are moot now (cancelled mid-wait).
        approvalStore.deleteForRoot(sessionId);

        // 1. The question becomes conversation truth — BEFORE the task exists.
        //    A file attached since the last turn is recorded on this message
        //    (metadata); the model reads the notice with the question, the
        //    text stays what the user typed.
        TokenCounter tokenCounter = memoryStrategyFactory.create(def).resolveTokenCounter(def);
        //    An attachment that was removed since is announced the same way,
        //    so the model stops looking for it. Both are read off the record;
        //    the record itself is never rewritten.
        List<Message> history = isFirstMessage ? List.of()
                : conversationManager.loadCompleteHistory(session.conversationId()).messages();
        List<String> attached = AttachmentNotice.unannounced(session, history);
        List<String> detached = AttachmentNotice.unannouncedRemovals(session, history);
        AgentTurnWorker.appendUserMessage(conversationManager, session.conversationId(),
                userMessage, turnId, tokenCounter, AttachmentNotice.metadata(attached, detached));

        // 2.+3. Listen on the turn's channel, make the turn a task — the queue
        //        is the only registry of running work, nothing is tracked here.
        return startTurn(sessionId, turnId, 0, eventHandler, response -> {
            generateTitleIfNeeded(session, isFirstMessage, userMessage, response);
            return response;
        });
    }

    /**
     * The shared tail of every turn start: subscribe the caller to the turn's
     * channel, submit the {@code agent.turn} task, wrap the queue's await in
     * the handle's future. Channel cleanup is graceful — the queued tail
     * (Done included) is delivered before detaching.
     */
    private ChatTurnHandle startTurn(UUID sessionId, UUID turnId, int run,
                                     Consumer<StreamEvent> eventHandler,
                                     java.util.function.UnaryOperator<String> afterCompletion) {
        var subscription = sessionChannels.subscribeTurn(sessionId, turnId, eventHandler);
        String taskId = queue.submit(AgentTurnWorker.submission(turnId, run, sessionId, 0, null));
        CompletableFuture<String> future = CompletableFuture
                .supplyAsync(() -> awaitResult(taskId), turnExecutor)
                .whenComplete((response, error) -> subscription.close())
                .thenApply(afterCompletion);
        return new LocalChatTurnHandle(turnId, sessionId, future, () -> cancelChat(sessionId));
    }

    /**
     * The conversation's OPEN approval questions, oldest first. The stream
     * announces a request in the moment it is raised; a client that connects
     * later — or reattaches after a restart — reads the still-unanswered ones
     * here and shows their cards.
     */
    public List<ToolApproval> openApprovals(UUID rootSessionId) {
        return approvalStore.openForRoot(rootSessionId);
    }

    /**
     * The human's answer to an approval card — Deny, Allow once, or Allow for
     * this session. The card's identity is the {@code callId}; everything
     * else comes from the {@link ToolApprovalStore} entry, the ONE truth for
     * the open question. The decision travels as a task NOTIFICATION to the
     * parked tool task, which re-runs its gate on wake: an explicit
     * grant/denial wins (that is what makes "once" possible), a session-wide
     * approval becomes a standing rule first — so every parked sibling of the
     * same tool passes its own re-check without any sweeping.
     *
     * <p>No stream, no resume, no run: the turn never ended — it is suspended
     * on the tool task and continues on its ORIGINAL stream the moment the
     * tool finishes (or reports the denial).
     *
     * @return false when no entry exists for {@code callId} or its task is
     *         gone — a stale card; the caller just refreshes, which drops it
     */
    public boolean answerApproval(UUID rootSessionId, String callId, boolean approved,
                                  ai.mindconnect.agent.service.approval.ApprovalScope scope) {
        ToolApproval open = approvalStore.find(callId).orElse(null);
        if (open == null) {
            log.warn("No open approval for call {} — stale card, nothing to answer", callId);
            return false;
        }
        if (approved && scope == ai.mindconnect.agent.service.approval.ApprovalScope.SESSION
                && open.toolName() != null) {
            sessionService.approveToolForSession(rootSessionId, open.toolName());
            // Wake the parked siblings of the same tool: their gate re-check
            // now passes via the fresh rule. Explicit grant keeps it
            // deterministic even if a rule read would race the write.
            for (ToolApproval other : approvalStore.openForRoot(rootSessionId)) {
                if (other.callId().equals(callId)) continue;
                if (!open.toolName().equals(other.toolName())) continue;
                queue.notify(other.toolTaskId(),
                        ai.mindconnect.agent.service.approval.ApprovalNotifications.approvalGranted());
                approvalStore.delete(other.callId());
                log.info("Session approval of '{}' released parked call {} as well",
                        open.toolName(), other.callId());
            }
        }
        boolean delivered = queue.notify(open.toolTaskId(), approved
                ? ai.mindconnect.agent.service.approval.ApprovalNotifications.approvalGranted()
                : ai.mindconnect.agent.service.approval.ApprovalNotifications.approvalDenied());
        approvalStore.delete(callId);
        if (!delivered) {
            log.warn("Approval for call {} could not be delivered — task {} is gone (restart/cancel)",
                    callId, open.toolTaskId());
            return false;
        }
        log.info("Approval answer for call {} ({}, scope {}) delivered to task {}",
                callId, approved ? "granted" : "denied", scope, open.toolTaskId());
        return true;
    }

    /**
     * Cooperatively cancels the session's running turn — found from domain
     * state alone: the last user CHAT message carries the turnId (persisted
     * before the task was submitted), and the task id is derived from it.
     * No scan, no map. The cancel cascades over the task tree, so sub-agent
     * turns die with their parent.
     */
    /**
     * What {@link #attach} hands back: the live subscription plus everything
     * a reconnecting client needs to orient itself — whether a turn is
     * running right now, and which part of the stream the buffer still
     * covers. {@code firstBufferedSeq > afterSeq + 1} means the replay has a
     * gap; the client refreshes from the persisted history instead of
     * trusting the tail.
     */
    public record Attachment(Subscription subscription, UUID liveTurnId, Integer liveRun,
                             long firstBufferedSeq, long latestSeq) {
    }

    /**
     * Attach to the session's stream: replay after {@code afterSeq}, then
     * live. This is the reconnect story — a client whose stream died (or
     * that just opened the session) resumes with its last seq and receives
     * everything it missed that the ring buffer still holds, the running
     * turn's partial included.
     */
    public Attachment attach(UUID sessionId, long afterSeq,
                             Consumer<Channel.Event<SessionEvent>> consumer) {
        AgentSession session = sessionService.findSession(sessionId);
        var history = conversationManager.loadCompleteHistory(session.conversationId());
        UUID liveTurnId = history.currentTurnId()
                .filter(turnId -> queue
                        .get(AgentTurnWorker.taskIdFor(turnId, history.currentRun()))
                        .filter(task -> !task.status().terminal())
                        .isPresent())
                .orElse(null);
        long firstBuffered = sessionChannels.earliestBufferedSeq(sessionId);
        long latest = sessionChannels.lastSeq(sessionId);
        Subscription subscription = sessionChannels.subscribe(sessionId, afterSeq, consumer);
        return new Attachment(subscription, liveTurnId,
                liveTurnId == null ? null : history.currentRun(), firstBuffered, latest);
    }

    public boolean cancelChat(UUID sessionId) {
        AgentSession session = sessionService.findSession(sessionId);
        var history = conversationManager.loadCompleteHistory(session.conversationId());
        boolean cancelled = history.currentTurnId()
                .map(turnId -> queue.get(AgentTurnWorker.taskIdFor(turnId, history.currentRun()))
                        .filter(task -> !task.status().terminal())
                        .map(task -> {
                            log.info("Cancelling chat turn {} for session {}", task.id(), sessionId);
                            return queue.cancel(task.id());
                        })
                        .orElse(false))
                .orElse(false);
        if (cancelled) {
            // The cascade killed the sub-turns too — their open approval
            // questions die with them, and so do their cards.
            approvalStore.deleteForRoot(sessionId);
            appendCancelStubs(session, history);
        }
        return cancelled;
    }

    /**
     * Closes the cancelled turn's open tool calls with a synthetic failed
     * TOOL_RESULT — the cascade killed their tasks before they could write
     * one, and an unpaired TOOL_CALL haunts everything downstream: the UI
     * card spins forever, the model sees a call without an answer, the fold
     * drags an eternally-open call along. A tool that was mid-flight and
     * still writes its real result loses against the stub — the worker
     * re-checks for an existing result right before its append.
     */
    private void appendCancelStubs(AgentSession session,
                                   ai.mindconnect.message.domain.ConversationHistory history) {
        var turn = history.currentTurn().orElse(null);
        if (turn == null) return;
        var open = ai.mindconnect.agent.service.round.ToolCalls
                .of(turn.messages()).open();
        for (var call : open) {
            TurnMessage stub = TurnMessage.toolResult(call.callId(), call.name(),
                    "Cancelled by user before the tool finished", true);
            conversationManager.addMessageToConversation(
                    session.conversationId(), session.agentDefinitionId(), stub.senderType(),
                    stub.type(), stub.content(), turn.turnId(), history.currentRun(),
                    stub.metadata());
            log.info("Cancel stub written for open call {} ({})", call.callId(), call.name());
        }
    }

    /** Blocks (a virtual thread) until the task is terminal and maps its ending. */
    private String awaitResult(String taskId) {
        TaskRecord terminal;
        try {
            terminal = queue.await(taskId, TURN_TIMEOUT);
        } catch (ai.mindconnect.taskqueue.TaskQueueException e) {
            // A turn parked at an approval gate can outwait any stream. The
            // await gives up, the TASK keeps waiting — answering later still
            // completes it; a reload then shows the persisted result.
            log.warn("Stopped awaiting task {} ({}) — the task itself lives on", taskId, e.getMessage());
            return "";
        }
        if (terminal.status() == TaskStatus.CANCELLED
                || (terminal.status() == TaskStatus.COMPLETED && "CANCELLED".equals(terminal.result()))) {
            throw new CancellationException("Turn cancelled");
        }
        if (terminal.status() != TaskStatus.COMPLETED) {
            String reason = terminal.failure() != null
                    ? terminal.failure().message() : terminal.status().name();
            throw new IllegalStateException("Chat turn failed: " + reason);
        }
        return terminal.result() == null ? "" : terminal.result();
    }

    // ── Title generation ──────────────────────────────────────────────────

    /**
     * Generates a title for a brand-new session by asking a small helper agent
     * to summarise the first user/agent exchange. Failures fall back to the
     * user message itself.
     */
    private void generateTitleIfNeeded(AgentSession session, boolean isFirstMessage,
                                       String userMessage, String response) {
        if (!isFirstMessage || session.title() != null) return;
        if (response == null || response.isBlank()) return;   // no exchange yet (approval wait)
        String title;
        try {
            String input = "User: " + userMessage + "\nAgent: " + response;
            String generated = agentTaskRunner.run(StatelessAgentSeeder.TITLE_GENERATOR, input);
            title = (generated == null || generated.isBlank()) ? userMessage : generated;
        } catch (Exception e) {
            log.warn("Title generation failed — using user message as fallback: {}", e.getMessage());
            title = userMessage;
        }
        sessionService.updateTitle(session.id(), title);
    }

    // ── Memory ─────────────────────────────────────────────────────────────

    /**
     * Builds a fresh {@link WorkingMemory} snapshot from the live conversation.
     * Always built fresh — the persisted snapshot may be stale after
     * retroactive tool-result compression.
     */
    public WorkingMemory memorySnapshot(UUID sessionId) {
        AgentSession session = sessionService.findSession(sessionId);
        AgentDefinition def = effectiveDefinition(session);
        AuthenticationInfo auth = authFor(session);
        return WorkingMemoryBuilder.build(promptRenderer, memoryStrategyFactory.create(def),
                def, session, auth);
    }

    /**
     * Asks the configured memory strategy to compress unsummarized messages
     * into conversation summaries. Refreshes the persisted working-memory
     * snapshot afterwards.
     *
     * @return number of messages that were compressed
     */
    public int compressMemory(UUID sessionId) {
        AgentSession session = sessionService.findSession(sessionId);
        AgentDefinition def = effectiveDefinition(session);
        AuthenticationInfo auth = authFor(session);

        MemoryStrategy strategy = memoryStrategyFactory.create(def);
        MemoryStrategy.CompressResult result = strategy.compress(def, session, auth);

        if (!result.isEmpty()) {
            try {
                WorkingMemory stats = WorkingMemoryBuilder.build(promptRenderer, strategy, def, session, auth);
                workingMemoryRepository.save(session.id(), auth, stats);
            } catch (Exception e) {
                log.warn("Failed to save working memory after compression: {}", e.getMessage());
            }
        }
        return result.compressedMessages();
    }

    private static AuthenticationInfo authFor(AgentSession session) {
        return AuthenticationInfo.of(session.userId(), session.namespace());
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
