package ai.mindconnect.agent.runtime.service;

import ai.mindconnect.message.domain.ChatTurnId;
import ai.mindconnect.agent.SessionId;
import ai.mindconnect.agent.runtime.domain.AgentDefinition;
import ai.mindconnect.agent.runtime.domain.AgentSession;
import ai.mindconnect.message.domain.ContentPart;
import ai.mindconnect.message.domain.Message;
import ai.mindconnect.agent.runtime.service.prompt.AttachmentNotice;
import ai.mindconnect.agent.runtime.service.prompt.AttachmentParts;
import ai.mindconnect.agent.runtime.domain.StreamEvent;
import ai.mindconnect.agent.runtime.memory.domain.WorkingMemory;
import ai.mindconnect.agent.runtime.memory.port.in.MemoryStrategy;
import ai.mindconnect.agent.runtime.memory.port.in.MemoryStrategyFactory;
import ai.mindconnect.agent.runtime.memory.port.out.WorkingMemoryRepository;
import ai.mindconnect.agent.runtime.port.in.AgentTaskRunner;
import ai.mindconnect.agent.runtime.port.in.ChatTurnHandle;
import ai.mindconnect.agent.runtime.domain.TurnResult;
import ai.mindconnect.agent.runtime.port.out.AgentDefinitionRepository;
import ai.mindconnect.agent.runtime.port.out.PromptRenderer;
import ai.mindconnect.agent.runtime.port.out.TokenCounter;
import ai.mindconnect.agent.runtime.service.approval.ApprovalNotifications;
import ai.mindconnect.agent.runtime.service.approval.ApprovalScope;
import ai.mindconnect.agent.runtime.domain.ToolApproval;
import ai.mindconnect.agent.runtime.port.out.ToolApprovalRepository;
import ai.mindconnect.agent.runtime.service.round.ToolCalls;
import ai.mindconnect.agent.runtime.service.stream.SessionChannels;
import ai.mindconnect.agent.runtime.service.stream.UserChannels;
import ai.mindconnect.agent.runtime.service.stream.UserEvent;
import ai.mindconnect.agent.runtime.service.stream.SessionEvent;
import ai.mindconnect.channel.Channel;
import ai.mindconnect.channel.Subscription;
import ai.mindconnect.agent.runtime.service.round.TurnMessage;
import ai.mindconnect.agent.runtime.service.task.AgentTurnWorker;
import ai.mindconnect.agent.runtime.service.task.SessionTitleWorker;
import ai.mindconnect.agent.runtime.service.turn.LocalChatTurnHandle;
import ai.mindconnect.agent.ScopeSupplier;
import ai.mindconnect.agent.ThreadBoundScope;
import ai.mindconnect.agent.runtime.service.task.ScopeTaskAdvisor;
import ai.mindconnect.taskqueue.TaskListener;
import ai.mindconnect.taskqueue.TaskSubmission;
import ai.mindconnect.agent.runtime.service.turn.WorkingMemoryBuilder;
import ai.mindconnect.agent.AuthenticationInfo;
import ai.mindconnect.common.PageRequest;
import ai.mindconnect.llm.domain.ToolDefinition;
import ai.mindconnect.message.port.in.ConversationManager;
import ai.mindconnect.taskqueue.TaskQueue;
import ai.mindconnect.taskqueue.TaskRecord;
import ai.mindconnect.taskqueue.TaskStatus;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
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

    /** Upper bound on one turn, sub-agents included — a safety net, not a target: a turn parked at a gate nobody answers. */
    private static final java.time.Duration TURN_TIMEOUT = java.time.Duration.ofHours(3);

    private final AgentSessionService sessionService;
    private final AgentDefinitionRepository definitionRepository;
    private final ConversationManager conversationManager;
    private final MemoryStrategyFactory memoryStrategyFactory;
    private final WorkingMemoryRepository workingMemoryRepository;
    private final PromptRenderer promptRenderer;
    private final SessionChannels sessionChannels;
    private final UserChannels userChannels;
    private final TaskQueue queue;
    private final ToolApprovalRepository approvalStore;
    /** Where this runtime works: a turn's handle is completed in the turn's own scope. */
    private final ScopeSupplier scope;
    /** Turns a caller holds a handle for, by task id — completed from the queue's listener, no thread waits. */
    private final java.util.Map<String, CompletableFuture<String>> awaitingTurns = new ConcurrentHashMap<>();
    /** Turns whose task ended but whose title task still runs, keyed by the title task's id. */
    private final java.util.Map<String, PendingTitle> awaitingTitles = new ConcurrentHashMap<>();

    /**
     * Per session, the stream position where a handle last stopped at the approval gate —
     * where the answer's handle continues. Dropped when the turn ends.
     */
    private final java.util.Map<SessionId, Long> resumeCursors = new ConcurrentHashMap<>();

    /** What a parked call reads when a new message ends its turn. */
    static final String SUPERSEDED_DENIAL = "Not approved: superseded by a new message";
    /** What any other open call of that turn reads. */
    static final String SUPERSEDED_CANCEL = "Cancelled: superseded by a new message";

    private record PendingTitle(TaskRecord turn, CompletableFuture<String> outcome) {}
    private final ai.mindconnect.agent.runtime.service.prompt.InstructionFiles instructions;
    private final ai.mindconnect.agent.runtime.skill.SkillCatalog skills;

    public AgentChatService(AgentSessionService sessionService,
                            AgentDefinitionRepository definitionRepository,
                            ConversationManager conversationManager,
                            MemoryStrategyFactory memoryStrategyFactory,
                            WorkingMemoryRepository workingMemoryRepository,
                            PromptRenderer promptRenderer,
                            SessionChannels sessionChannels,
                            UserChannels userChannels,
                            TaskQueue queue,
                            ToolApprovalRepository approvalStore,
                            ai.mindconnect.agent.runtime.service.prompt.InstructionFiles instructions) {
        this(sessionService, definitionRepository, conversationManager, memoryStrategyFactory,
                workingMemoryRepository, promptRenderer, sessionChannels, userChannels,
                queue, approvalStore, instructions,
                ai.mindconnect.agent.runtime.skill.SkillCatalog.none(), ScopeSupplier.local());
    }

    public AgentChatService(AgentSessionService sessionService,
                            AgentDefinitionRepository definitionRepository,
                            ConversationManager conversationManager,
                            MemoryStrategyFactory memoryStrategyFactory,
                            WorkingMemoryRepository workingMemoryRepository,
                            PromptRenderer promptRenderer,
                            SessionChannels sessionChannels,
                            UserChannels userChannels,
                            TaskQueue queue,
                            ToolApprovalRepository approvalStore,
                            ai.mindconnect.agent.runtime.service.prompt.InstructionFiles instructions,
                            ai.mindconnect.agent.runtime.skill.SkillCatalog skills,
                            ScopeSupplier scope) {
        this.sessionService = sessionService;
        this.definitionRepository = definitionRepository;
        this.conversationManager = conversationManager;
        this.memoryStrategyFactory = memoryStrategyFactory;
        this.workingMemoryRepository = workingMemoryRepository;
        this.promptRenderer = promptRenderer;
        this.sessionChannels = sessionChannels;
        this.userChannels = userChannels;
        this.queue = queue;
        this.approvalStore = approvalStore;
        this.scope = scope == null ? ScopeSupplier.local() : scope;
        this.instructions = instructions;
        this.skills = skills == null ? ai.mindconnect.agent.runtime.skill.SkillCatalog.none() : skills;
        // The queue tells us when a turn ended; no thread of ours waits for it.
        if (queue != null) {
            queue.addListener(new TaskListener() {
                @Override public void onTerminal(TaskRecord task) { turnEnded(task); }
            });
        }
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
     * terminal — title generation included: the title is a task of its own
     * behind the turn, and the future waits for it, so a caller that reads the
     * session right after the answer finds the chat named.
     */
    public ChatTurnHandle submitChat(SessionId sessionId, String userMessage,
                                     Consumer<StreamEvent> eventHandler) {
        return submitChat(sessionId, ContentPart.text(userMessage), eventHandler);
    }

    /**
     * Same, for a message made of content parts — text plus the images and
     * documents the caller sends with it. Files attached to the session
     * since the last turn ride along as parts of their own (images, PDFs)
     * or as a notice (everything else); see {@link AttachmentParts}.
     */
    public ChatTurnHandle submitChat(SessionId sessionId, List<ContentPart> parts,
                                     Consumer<StreamEvent> eventHandler) {
        return submit(sessionId, parts, eventHandler, false);
    }

    /**
     * Starts a chat turn that stops at the approval gate: the handle's
     * {@link ChatTurnHandle#outcome()} completes {@code INCOMPLETE} with the open
     * questions as soon as a tool call waits for a human, and {@code events} hears
     * nothing after that. {@link #approve} or {@link #deny} continue the same turn
     * with a new handle. A turn that asks nothing completes like {@link #submitChat}.
     */
    public ChatTurnHandle sendChat(SessionId sessionId, String userMessage, Consumer<StreamEvent> events) {
        return sendChat(sessionId, ContentPart.text(userMessage), events);
    }

    /** Same, for a message made of content parts. */
    public ChatTurnHandle sendChat(SessionId sessionId, List<ContentPart> parts, Consumer<StreamEvent> events) {
        return submit(sessionId, parts, events, true);
    }

    private ChatTurnHandle submit(SessionId sessionId, List<ContentPart> parts,
                                  Consumer<StreamEvent> eventHandler, boolean stopAtGate) {
        AgentSession session = sessionService.findSession(sessionId);
        AgentDefinition def = effectiveDefinition(session);
        String userMessage = ContentPart.textOf(parts);

        boolean isFirstMessage = conversationManager
                .loadHistory(session.conversationId(), new PageRequest(0, 1)).isEmpty();

        ChatTurnId turnId = ChatTurnId.random();

        // A new turn can only start when the previous one is over. One that
        // still waits for an approval is ended here, its questions denied —
        // before the new question is written, so the conversation reads in order.
        supersedeWaitingTurn(session);

        // 1. The question becomes conversation truth — BEFORE the task exists.
        //    A file attached since the last turn is recorded on this message
        //    (metadata); the model reads the notice with the question, the
        //    text stays what the user typed. An image or PDF among them also
        //    becomes a part of the message, so a model that reads it sees it.
        TokenCounter tokenCounter = memoryStrategyFactory.create(def).resolveTokenCounter(def);
        //    An attachment that was removed since is announced the same way,
        //    so the model stops looking for it. Both are read off the record;
        //    the record itself is never rewritten.
        List<Message> history = isFirstMessage ? List.of()
                : conversationManager.loadCompleteHistory(session.conversationId()).messages();
        List<String> attached = AttachmentNotice.unannounced(session, history);
        List<String> detached = AttachmentNotice.unannouncedRemovals(session, history);
        AgentTurnWorker.appendUserMessage(conversationManager, session.conversationId(), session.userId(),
                AttachmentParts.withAttachments(parts, session, attached), turnId, tokenCounter,
                AttachmentNotice.metadata(attached, detached));

        // 2.+3. Listen on the turn's channel, make the turn a task — the queue
        //        is the only registry of running work, nothing is tracked here.
        return startTurn(session, turnId, 0, eventHandler, stopAtGate);
    }

    /**
     * The shared tail of every turn start: subscribe the caller to the turn's
     * channel, submit the {@code agent.turn} task, wrap the queue's await in
     * the handle's future. Channel cleanup is graceful — the queued tail
     * (Done included) is delivered before detaching.
     *
     * <p>The user's stream hears the turn begin and end here — the one place
     * every top-level turn passes through, whichever client submitted it.
     */
    private ChatTurnHandle startTurn(AgentSession session, ChatTurnId turnId, int run,
                                     Consumer<StreamEvent> eventHandler, boolean stopAtGate) {
        SessionId sessionId = session.id();
        // The handle replays from here, so it hears the turn from its first event on.
        long startSeq = sessionChannels.lastSeq(sessionId);
        TaskSubmission submission = AgentTurnWorker.submission(turnId, run, sessionId, 0, null);
        CompletableFuture<String> turn = new CompletableFuture<>();
        // Registered before the submit: a turn that ends before submit() returns still finds its future.
        awaitingTurns.put(submission.id(), turn);
        turn.whenComplete((response, error) -> {
            resumeCursors.remove(sessionId);
            userChannels.publish(session.userId(),
                    new UserEvent.TurnFinished(sessionId, turnId, outcomeOf(error)));
        });
        try {
            queue.submit(submission);
        } catch (RuntimeException e) {
            awaitingTurns.remove(submission.id());
            throw e;
        }
        userChannels.publish(session.userId(), new UserEvent.TurnStarted(sessionId, turnId));
        // An untitled chat is named from this message, in parallel with the turn: the title
        // task needs only what the user wrote, and the handle waits for it at the end. A
        // sub-agent's session is no chat of the user's: it keeps the name its parent gave it.
        if (session.title() == null && session.parentSessionId() == null) {
            queue.submit(SessionTitleWorker.submission(sessionId, turnId));
        }
        // An idempotent re-submit of a task that already ended fires no listener: read it off the queue.
        queue.get(submission.id()).filter(task -> task.status().terminal()).ifPresent(this::turnEnded);
        return handleFor(session, turnId, run, startSeq, eventHandler, stopAtGate);
    }

    /**
     * One caller's view of a turn: its events after {@code afterSeq}, its final
     * answer, and — the moment a tool call waits at the approval gate — an
     * {@code INCOMPLETE} outcome listing the open questions.
     *
     * <p>Every handle of a turn shares the turn's one future; the timeout is the
     * handle's own, so a caller that stops waiting does not end the turn for
     * anybody else. With {@code stopAtGate} the handle's events end at the gate:
     * the answer's handle picks them up from there (see {@link #approve}).
     * Without it the handler hears the turn to its end, as before.
     */
    private ChatTurnHandle handleFor(AgentSession session, ChatTurnId turnId, int run, long afterSeq,
                                     Consumer<StreamEvent> eventHandler, boolean stopAtGate) {
        SessionId sessionId = session.id();
        CompletableFuture<String> result = watchTurn(AgentTurnWorker.taskIdFor(turnId, run))
                .copy().orTimeout(TURN_TIMEOUT.toSeconds(), java.util.concurrent.TimeUnit.SECONDS);
        CompletableFuture<TurnResult> outcome = new CompletableFuture<>();
        Runnable cancel = () -> cancelChat(sessionId);
        LocalChatTurnHandle handle = new LocalChatTurnHandle(turnId, sessionId, result, outcome, cancel);

        // An answer that leaves other questions open continues nothing yet.
        List<ToolApproval> stillOpen = approvalStore.openForRoot(sessionId);
        if (!stillOpen.isEmpty()) {
            outcome.complete(TurnResult.incomplete(turnId, stillOpen));
            if (stopAtGate) return handle;
        }

        Subscription subscription = sessionChannels.subscribeTurn(sessionId, turnId, afterSeq, event -> {
            if (stopAtGate && outcome.isDone()) return;
            eventHandler.accept(event.value());
            if (event.value() instanceof StreamEvent.ApprovalRequested asked && !outcome.isDone()
                    // a replayed question that was answered meanwhile asks nothing any more
                    && approvalStore.find(sessionId, asked.callId()).isPresent()) {
                resumeCursors.put(sessionId, event.seq());
                outcome.complete(TurnResult.incomplete(turnId, approvalStore.openForRoot(sessionId)));
            }
        });
        if (stopAtGate) {
            outcome.whenComplete((r, error) -> subscription.close());
        }
        result.whenComplete((text, error) -> {
            subscription.close();
            if (error != null) outcome.completeExceptionally(error);
            else outcome.complete(TurnResult.completed(turnId, text));
        });
        return handle;
    }

    /**
     * The future of the turn task {@code taskId}, shared by every handle. A turn
     * that ended before anybody watched it is read off the queue.
     */
    private CompletableFuture<String> watchTurn(String taskId) {
        CompletableFuture<String> created = new CompletableFuture<>();
        CompletableFuture<String> existing = awaitingTurns.putIfAbsent(taskId, created);
        if (existing != null) return existing;
        queue.get(taskId).filter(task -> task.status().terminal()).ifPresent(this::turnEnded);
        return created;
    }

    /**
     * Continues the turn waiting on {@code callId} with "run it". Delivers the
     * answer like {@link #answerApproval} and hands back the turn from where the
     * caller's last handle stopped: its events, its answer, or the next question.
     *
     * @return empty when this chat has no open question {@code callId} or its task is gone
     */
    public java.util.Optional<ChatTurnHandle> approve(SessionId rootSessionId, String callId,
                                                      ApprovalScope scope, Consumer<StreamEvent> events) {
        return continueAfterAnswer(rootSessionId, callId, true, scope, events);
    }

    /** Continues the turn waiting on {@code callId} with "do not run it"; see {@link #approve}. */
    public java.util.Optional<ChatTurnHandle> deny(SessionId rootSessionId, String callId,
                                                   Consumer<StreamEvent> events) {
        return continueAfterAnswer(rootSessionId, callId, false, ApprovalScope.ONCE, events);
    }

    private java.util.Optional<ChatTurnHandle> continueAfterAnswer(SessionId rootSessionId, String callId,
                                                                   boolean approved, ApprovalScope scope,
                                                                   Consumer<StreamEvent> events) {
        AgentSession session = sessionService.findSession(rootSessionId);
        var history = conversationManager.loadCompleteHistory(session.conversationId());
        ChatTurnId turnId = history.currentTurnId().orElse(null);
        if (turnId == null) return java.util.Optional.empty();
        // Read before answering: what the tool does next must land after the cursor.
        long cursor = resumeCursors.getOrDefault(rootSessionId, sessionChannels.lastSeq(rootSessionId));
        if (!answerApproval(rootSessionId, callId, approved, scope)) return java.util.Optional.empty();
        return java.util.Optional.of(handleFor(session, turnId, history.currentRun(), cursor, events, true));
    }

    /**
     * Ends the session's turn if it waits at the approval gate — a new message
     * supersedes it. It goes the way of a cancel, so it takes no further round
     * that would write into the new turn: the parked calls are closed as not
     * approved, anything else still open as cancelled.
     */
    private void supersedeWaitingTurn(AgentSession session) {
        List<ToolApproval> open = approvalStore.openForRoot(session.id());
        if (open.isEmpty()) return;
        java.util.Set<String> parked = new java.util.HashSet<>();
        for (ToolApproval approval : open) parked.add(approval.callId());
        log.info("New message on session {} supersedes the turn waiting for {} approval(s)",
                session.id(), open.size());
        boolean cancelled = cancelTurn(session, call -> parked.contains(call.callId())
                ? TurnMessage.toolResult(call.callId(), call.name(), SUPERSEDED_DENIAL, true)
                        .with("approval", "denied").with("reason", "superseded")
                : TurnMessage.toolResult(call.callId(), call.name(), SUPERSEDED_CANCEL, true));
        if (!cancelled) {
            approvalStore.deleteForRoot(session.id());   // cards of a turn that is already gone
        }
    }

    /** How the turn ended, read off the await's failure — or its absence. */
    private static UserEvent.TurnOutcome outcomeOf(Throwable error) {
        if (error == null) return UserEvent.TurnOutcome.COMPLETED;
        Throwable cause = error instanceof java.util.concurrent.CompletionException && error.getCause() != null
                ? error.getCause() : error;
        return cause instanceof CancellationException
                ? UserEvent.TurnOutcome.CANCELLED : UserEvent.TurnOutcome.FAILED;
    }

    /**
     * Announces {@code event} on the stream of the user who owns
     * {@code sessionId}. Best-effort: a session that cannot be read is not
     * announced, and the operation that raised the event is not affected.
     */
    private void announce(SessionId sessionId, UserEvent event) {
        try {
            userChannels.publish(sessionService.findSession(sessionId).userId(), event);
        } catch (RuntimeException e) {
            log.debug("User event {} for session {} not announced: {}", event, sessionId, e.toString());
        }
    }

    /**
     * The conversation's OPEN approval questions, oldest first. The stream
     * announces a request in the moment it is raised; a client that connects
     * later — or reattaches after a restart — reads the still-unanswered ones
     * here and shows their cards.
     */
    public List<ToolApproval> openApprovals(SessionId rootSessionId) {
        return approvalStore.openForRoot(rootSessionId);
    }

    /**
     * The human's answer to an approval card — Deny, Allow once, or Allow for
     * this session. The card's identity is its chat and the {@code callId} —
     * the provider's id alone is unique only within one response; everything
     * else comes from the {@link ToolApprovalRepository} entry, the ONE truth for
     * the open question. The decision travels as a task NOTIFICATION to the
     * parked tool task, which re-runs its gate on wake: an explicit
     * grant/denial wins (that is what makes "once" possible), a session-wide
     * approval becomes a standing rule first — so every parked sibling of the
     * same tool passes its own re-check without any sweeping.
     *
     * <p>No stream, no resume, no run: the turn never ended — it is suspended
     * on the tool task and continues on its ORIGINAL stream the moment the
     * tool finishes (or reports the denial).
     * A caller that wants a handle on what follows answers through
     * {@link #approve} or {@link #deny} instead.
     *
     * @return false when this chat has no open question {@code callId} or its task is
     *         gone — a stale card; the caller just refreshes, which drops it
     */
    public boolean answerApproval(SessionId rootSessionId, String callId, boolean approved,
                                  ApprovalScope scope) {
        ToolApproval open = approvalStore.find(rootSessionId, callId).orElse(null);
        if (open == null) {
            log.warn("No open approval for call {} in session {} — stale card, nothing to answer",
                    callId, rootSessionId);
            return false;
        }
        if (approved && scope == ApprovalScope.SESSION
                && open.toolName() != null) {
            sessionService.approveToolForSession(rootSessionId, open.toolName());
            // Wake the parked siblings of the same tool: their gate re-check
            // now passes via the fresh rule. Explicit grant keeps it
            // deterministic even if a rule read would race the write.
            for (ToolApproval other : approvalStore.openForRoot(rootSessionId)) {
                if (other.callId().equals(callId)) continue;
                if (!open.toolName().equals(other.toolName())) continue;
                queue.notify(other.toolTaskId(),
                        ApprovalNotifications.approvalGranted());
                approvalStore.delete(rootSessionId, other.callId());
                log.info("Session approval of '{}' released parked call {} as well",
                        open.toolName(), other.callId());
            }
        }
        boolean delivered = queue.notify(open.toolTaskId(), approved
                ? ApprovalNotifications.approvalGranted()
                : ApprovalNotifications.approvalDenied());
        approvalStore.delete(rootSessionId, callId);
        if (!delivered) {
            log.warn("Approval for call {} could not be delivered — task {} is gone (restart/cancel)",
                    callId, open.toolTaskId());
            return false;
        }
        log.info("Approval answer for call {} ({}, scope {}) delivered to task {}",
                callId, approved ? "granted" : "denied", scope, open.toolTaskId());
        announce(rootSessionId, new UserEvent.ApprovalAnswered(rootSessionId, callId, approved));
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
    public record Attachment(Subscription subscription, ChatTurnId liveTurnId, Integer liveRun,
                             long firstBufferedSeq, long latestSeq) {
    }

    /**
     * Attach to the session's stream: replay after {@code afterSeq}, then
     * live. This is the reconnect story — a client whose stream died (or
     * that just opened the session) resumes with its last seq and receives
     * everything it missed that the ring buffer still holds, the running
     * turn's partial included.
     */
    public Attachment attach(SessionId sessionId, long afterSeq,
                             Consumer<Channel.Event<SessionEvent>> consumer) {
        AgentSession session = sessionService.findSession(sessionId);
        var history = conversationManager.loadCompleteHistory(session.conversationId());
        ChatTurnId liveTurnId = history.currentTurnId()
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

    public boolean cancelChat(SessionId sessionId) {
        return cancelTurn(sessionService.findSession(sessionId), call -> TurnMessage.toolResult(
                call.callId(), call.name(), "Cancelled by user before the tool finished", true));
    }

    /** Cancels the session's running turn; {@code stub} closes each call it left open. */
    private boolean cancelTurn(AgentSession session,
                               java.util.function.Function<ToolCalls.Call, TurnMessage> stub) {
        SessionId sessionId = session.id();
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
            appendCancelStubs(session, history, stub);
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
                                   ai.mindconnect.message.domain.ConversationHistory history,
                                   java.util.function.Function<ToolCalls.Call, TurnMessage> stubFor) {
        var turn = history.currentTurn().orElse(null);
        if (turn == null) return;
        var open = ToolCalls
                .of(turn.messages()).open();
        for (var call : open) {
            TurnMessage stub = stubFor.apply(call);
            conversationManager.addMessageToConversation(
                    session.conversationId(), session.agentDefinitionId().value(), stub.senderType(),
                    stub.type(), stub.content(), turn.turnId(), history.currentRun(),
                    stub.metadata());
            log.info("Cancel stub written for open call {} ({})", call.callId(), call.name());
        }
    }

    /** Blocks (a virtual thread) until the task is terminal and maps its ending. */
    /**
     * The queue's word that a task ended. A turn somebody holds a handle for
     * gets its future completed — in the turn's own scope, so whatever the
     * caller chained on the handle (the REST stream's close, the chat UI's
     * final render) works where the turn did.
     */
    void turnEnded(TaskRecord task) {
        if (SessionTitleWorker.TYPE.equals(task.type())) {
            PendingTitle pending = awaitingTitles.remove(task.id());
            if (pending != null) completeInScope(pending.turn(), pending.outcome());
            return;
        }
        CompletableFuture<String> outcome = awaitingTurns.remove(task.id());
        if (outcome == null) return;
        // A root turn that named its chat submitted a title task with a known id: the
        // handle resolves once that one is done too, like the old executor waited for it.
        String titleId = titleTaskOf(task);
        if (titleId != null && queue.get(titleId).filter(t -> !t.status().terminal()).isPresent()) {
            PendingTitle previous = awaitingTitles.putIfAbsent(titleId, new PendingTitle(task, outcome));
            if (previous != null) {
                // Somebody already waits for this title: follow that wait instead of replacing it.
                previous.outcome().whenComplete((text, error) -> {
                    if (error != null) outcome.completeExceptionally(error);
                    else outcome.complete(text);
                });
                return;
            }
            // It may have ended between the check and the put: then nobody fires the listener for us.
            if (queue.get(titleId).filter(t -> t.status().terminal()).isPresent()
                    && awaitingTitles.remove(titleId) != null) {
                completeInScope(task, outcome);
            }
            return;
        }
        completeInScope(task, outcome);
    }

    /** The id the turn's title task would have, or null when the payload does not say. */
    private static String titleTaskOf(TaskRecord turn) {
        Object session = turn.payload().get(AgentTurnWorker.SESSION_ID);
        Object turnId = turn.payload().get(AgentTurnWorker.TURN_ID);
        if (session == null || turnId == null) return null;
        return SessionTitleWorker.taskIdFor(SessionId.of(session.toString()), ChatTurnId.of(turnId.toString()));
    }

    private void completeInScope(TaskRecord task, CompletableFuture<String> outcome) {
        Runnable complete = () -> completeFrom(task, outcome);
        if (scope instanceof ThreadBoundScope bound) {
            ScopeTaskAdvisor.scopeIfAny(task).ifPresentOrElse(s -> bound.runIn(s, complete), complete);
        } else {
            complete.run();
        }
    }

    private static void completeFrom(TaskRecord terminal, CompletableFuture<String> outcome) {
        if (terminal.status() == TaskStatus.CANCELLED
                || (terminal.status() == TaskStatus.COMPLETED && "CANCELLED".equals(terminal.result()))) {
            outcome.completeExceptionally(new CancellationException("Turn cancelled"));
            return;
        }
        if (terminal.status() != TaskStatus.COMPLETED) {
            String reason = terminal.failure() != null
                    ? terminal.failure().message() : terminal.status().name();
            outcome.completeExceptionally(new IllegalStateException("Chat turn failed: " + reason));
            return;
        }
        outcome.complete(terminal.result() == null ? "" : terminal.result());
    }

    // ── Memory ─────────────────────────────────────────────────────────────

    /**
     * Builds a fresh {@link WorkingMemory} snapshot from the live conversation.
     * Always built fresh — the persisted snapshot may be stale after
     * retroactive tool-result compression.
     */
    public WorkingMemory memorySnapshot(SessionId sessionId) {
        AgentSession session = sessionService.findSession(sessionId);
        AgentDefinition def = effectiveDefinition(session);
        AuthenticationInfo auth = authFor(session);
        return WorkingMemoryBuilder.build(promptRenderer, memoryStrategyFactory.create(def),
                def, session, auth, instructions, skills);
    }

    /**
     * Asks the configured memory strategy to compress unsummarized messages
     * into conversation summaries. Refreshes the persisted working-memory
     * snapshot afterwards.
     *
     * @return number of messages that were compressed
     */
    public int compressMemory(SessionId sessionId) {
        AgentSession session = sessionService.findSession(sessionId);
        AgentDefinition def = effectiveDefinition(session);
        AuthenticationInfo auth = authFor(session);

        MemoryStrategy strategy = memoryStrategyFactory.create(def);
        MemoryStrategy.CompressResult result = strategy.compress(def, session, auth);

        if (!result.isEmpty()) {
            try {
                WorkingMemory stats = WorkingMemoryBuilder.build(promptRenderer, strategy, def, session,
                        auth, instructions, skills);
                workingMemoryRepository.save(session.id(), auth, stats);
            } catch (Exception e) {
                log.warn("Failed to save working memory after compression: {}", e.getMessage());
            }
        }
        return result.compressedMessages();
    }

    private static AuthenticationInfo authFor(AgentSession session) {
        return AuthenticationInfo.of(session.userId());
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
