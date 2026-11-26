package ai.mindconnect.agent.protocol.runtime;

import ai.mindconnect.agent.loop.domain.TurnMessage;
import ai.mindconnect.agent.runtime.api.domain.SessionEvent;
import ai.mindconnect.agent.loop.domain.TurnStreamEvent;
import ai.mindconnect.agent.runtime.api.domain.SessionStreamEvent;
import ai.mindconnect.agent.runtime.api.domain.TurnStatus;
import ai.mindconnect.agent.protocol.IncompleteReason;
import ai.mindconnect.agent.protocol.Response;
import ai.mindconnect.agent.protocol.ResponseError;
import ai.mindconnect.agent.protocol.ResponseStatus;
import ai.mindconnect.agent.protocol.Usage;
import ai.mindconnect.agent.protocol.api.Subscription;
import ai.mindconnect.agent.protocol.event.ResponseEvent;
import ai.mindconnect.agent.protocol.item.ConversationItem;
import ai.mindconnect.agent.protocol.item.ConversationItemRecord;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Assembles what a turn of the runtime reports into protocol items and
 * {@link ResponseEvent}s for ONE response. This is the whole translation —
 * pure state machine, no I/O, unit-testable without a runtime.
 *
 * <p>Buffered events replay to late subscribers ({@code afterSeq}); tokens
 * accumulate into the pending assistant message item, which is flushed when
 * a tool call, sub-agent call or the end of the turn interrupts the text.
 *
 * <p>Mapping notes: call ids are the response's own, and a result is paired
 * with its call through the runtime's call id. A call that hands its work
 * to another agent shows as that agent's {@code AgentCall} and its answer,
 * not as a function call. Sub-agent turns are not addressable responses on
 * this backend (yet) — {@code AgentCall.childResponseId} carries the
 * sub-session id instead, and what happens inside them is folded away.
 */
public final class ResponseAssembler {

    private final String responseId;
    private final String conversationId;
    private final String sessionId;
    private final String agentName;
    private final Instant createdAt = Instant.now();

    private final List<ConversationItemRecord> items = new ArrayList<>();
    private final List<ResponseEvent> events = new ArrayList<>();
    private final List<SubscriberSlot> subscribers = new CopyOnWriteArrayList<>();

    private final StringBuilder textBuffer = new StringBuilder();
    private String pendingTextItemId;
    private final StringBuilder reasoningBuffer = new StringBuilder();
    private String pendingReasoningItemId;
    /** The runtime's call id of an open function call, to the id its item carries. */
    private final Map<String, String> openToolCalls = new HashMap<>();
    /** The calls that hand their work to another agent: shown through the agent's turn, not as functions. */
    private final Set<String> delegatingCalls = new HashSet<>();
    private final Map<String, String> openAgentTasks = new HashMap<>();

    private final Map<String, Object> metadata = new HashMap<>();
    private long seq = 0;
    private int itemCounter = 0;
    /**
     * What the turn last reported it cost. Recorded from TurnUsage rather
     * than from the terminal event, so a response that fails or is cancelled
     * still carries the tokens it burned before it died.
     */
    private Usage usage = Usage.ZERO;
    private ResponseStatus status = ResponseStatus.IN_PROGRESS;
    private IncompleteReason incompleteReason;
    private ResponseError error;
    private Instant completedAt;

    /**
     * One subscriber. Until its backlog has been handed over, live events
     * queue here instead of going straight out — that is what keeps a reader
     * from seeing a later event before an earlier one without holding the
     * assembler's lock across the subscriber's I/O.
     */
    private static final class SubscriberSlot {
        private final Consumer<ResponseEvent> consumer;
        private final Deque<ResponseEvent> queued = new ArrayDeque<>();
        private boolean caughtUp;

        SubscriberSlot(Consumer<ResponseEvent> consumer) {
            this.consumer = consumer;
        }

        Consumer<ResponseEvent> consumer() {
            return consumer;
        }
    }

    public ResponseAssembler(String responseId, String conversationId,
                             String sessionId, String agentName) {
        this.responseId = responseId;
        this.conversationId = conversationId;
        this.sessionId = sessionId;
        this.agentName = agentName;
        emit(new ResponseEvent.Created(responseId, ++seq));
        emit(new ResponseEvent.InProgress(responseId, ++seq));
    }

    // ── what the turn reports ───────────────────────────────────────────────

    /** The tools through which an agent hands a task to another one. */
    private static final Set<String> DELEGATION_TOOLS = Set.of("run_agent", "run_agents");

    /** An event of the response's own turn. */
    public synchronized void accept(SessionEvent event) {
        if (status.terminal()) return;
        switch (event.known()) {
            case TurnStreamEvent.TextDelta token -> onToken(token.text());
            case TurnStreamEvent.Thinking thinking -> onThinking(thinking.text());
            case TurnStreamEvent.MessageWritten written -> onMessage(written.message());
            case TurnStreamEvent.TurnUsage spent -> usage = new Usage(spent.inputTokens(), spent.outputTokens());
            case SessionStreamEvent.Done done -> onEnded(done);
            case SessionStreamEvent.Error error -> {
                if (error.status() == TurnStatus.CANCELLED) {
                    cancelled();
                } else {
                    fail(error.message());
                }
            }
            // Status only, or told another way: nothing of them is an item.
            default -> { }
        }
    }

    /** A turn below the response's own has begun: an agent the response's agent called. */
    public synchronized void subAgentStarted(String turnId, String agentName, String sessionId) {
        if (status.terminal() || openAgentTasks.containsKey(turnId)) return;
        flushText();
        String callId = nextItemId("task");
        openAgentTasks.put(turnId, callId);
        addItem(new ConversationItem.AgentCall(callId, agentName, null, sessionId));
    }

    /** That agent's turn is over, with its answer. */
    public synchronized void subAgentEnded(String turnId, String text) {
        if (status.terminal()) return;
        String callId = openAgentTasks.remove(turnId);
        addItem(new ConversationItem.FunctionCallOutput(
                callId == null ? "task_unknown" : callId, text == null ? "" : text, false));
    }

    private void onMessage(TurnMessage message) {
        switch (message.type()) {
            case TOOL_CALL -> message.toolCalls().forEach(call -> {
                if (DELEGATION_TOOLS.contains(call.name())) {
                    delegatingCalls.add(call.callId());
                } else {
                    onToolCall(call.callId(), call.name(), call.arguments());
                }
            });
            case TOOL_RESULT -> {
                Object callId = message.metadata().get("callId");
                if (callId == null || !delegatingCalls.remove(callId)) {
                    onToolResult(callId == null ? null : String.valueOf(callId), message.text(),
                            Boolean.TRUE.equals(message.metadata().get("failed")));
                }
            }
            // The answer as it stands in the session: what was streamed, or what a reviewer made of it.
            case ASSISTANT -> onAnswer(message.text());
            default -> { }
        }
    }

    /**
     * The whole of an answer. It replaces what was streamed of it — a
     * reviewer may have revised it — and a model that does not stream is
     * heard here for the first time.
     */
    private void onAnswer(String text) {
        if (text == null || text.isEmpty() || text.contentEquals(textBuffer)) return;
        if (textBuffer.isEmpty()) {
            onToken(text);
            return;
        }
        textBuffer.setLength(0);
        textBuffer.append(text);
    }

    private void onEnded(SessionStreamEvent.Done done) {
        switch (done.status()) {
            case CANCELLED -> cancelled();
            case INCOMPLETE -> incomplete("MAX_OUTPUT_TOKENS".equals(done.incompleteReason())
                    ? IncompleteReason.MAX_OUTPUT_TOKENS : IncompleteReason.MAX_ROUNDS);
            default -> onDone();
        }
    }

    private void incomplete(IncompleteReason reason) {
        flushText();
        status = ResponseStatus.INCOMPLETE;
        incompleteReason = reason;
        completedAt = Instant.now();
        emit(new ResponseEvent.Incomplete(responseId, ++seq, incompleteReason));
    }

    /**
     * The turn waits for a human: the response ends {@code INCOMPLETE} with one
     * {@code ApprovalRequest} item per open question. The turn itself goes on
     * once answered — as a new response.
     */
    public synchronized void waitingForApproval(List<ConversationItem.ApprovalRequest> requests) {
        if (status.terminal()) return;
        flushText();
        requests.forEach(this::addItem);
        status = ResponseStatus.INCOMPLETE;
        incompleteReason = IncompleteReason.WAITING_FOR_APPROVAL;
        completedAt = Instant.now();
        emit(new ResponseEvent.Incomplete(responseId, ++seq, incompleteReason));
    }

    /** The turn ended without an outcome: it failed before it could report one. */
    public synchronized void fail(String message) {
        if (status.terminal()) return;
        flushText();
        status = ResponseStatus.FAILED;
        error = new ResponseError("turn_failed", message);
        completedAt = Instant.now();
        emit(new ResponseEvent.Failed(responseId, ++seq, error));
    }

    public synchronized void cancelled() {
        if (status.terminal()) return;
        flushText();
        status = ResponseStatus.CANCELLED;
        completedAt = Instant.now();
        emit(new ResponseEvent.Cancelled(responseId, ++seq));
    }

    // ── Protocol views ──────────────────────────────────────────────────────

    /** Backend-specific extras for the extension slot ({@code mc.*} keys), e.g. the turn id. */
    public synchronized void addMetadata(String key, Object value) {
        metadata.put(key, value);
    }

    public synchronized Response snapshot() {
        return new Response(responseId, conversationId, sessionId, agentName,
                status, incompleteReason, null, null, List.copyOf(items),
                usage, error, Map.copyOf(metadata), createdAt, completedAt);
    }

    public Subscription subscribe(long afterSeq, Consumer<ResponseEvent> consumer) {
        SubscriberSlot slot = new SubscriberSlot(consumer);
        List<ResponseEvent> backlog;
        synchronized (this) {
            backlog = events.stream().filter(e -> e.seq() > afterSeq).toList();
            subscribers.add(slot);          // not caught up yet: emit() queues
        }
        try {
            backlog.forEach(consumer);

            // Drain what arrived while the backlog was going out, and only
            // call the subscriber caught up once its queue is empty under the
            // lock — otherwise an event slipping in during the last drain
            // would jump ahead of the ones still queued. The subscriber's I/O
            // stays outside the lock throughout, so a slow reader cannot
            // stall the turn that is producing the events.
            while (true) {
                List<ResponseEvent> pending;
                synchronized (this) {
                    if (slot.queued.isEmpty()) {
                        slot.caughtUp = true;
                        return () -> subscribers.remove(slot);
                    }
                    pending = List.copyOf(slot.queued);
                    slot.queued.clear();
                }
                pending.forEach(consumer);
            }
        } catch (RuntimeException e) {
            // A subscriber that dies mid-backlog would otherwise stay
            // registered and never caught up, queueing the rest of the run
            // into a list nobody drains.
            subscribers.remove(slot);
            throw e;
        }
    }

    // ── internals ───────────────────────────────────────────────────────────

    private void onThinking(String text) {
        if (pendingReasoningItemId == null) {
            // Reasoning after text has begun (a model reflecting mid-answer) is
            // a new item after the message, not a slot shared with it.
            flushText();
            pendingReasoningItemId = nextItemId("rs");
            emit(new ResponseEvent.OutputItemAdded(responseId, ++seq,
                    new ConversationItemRecord(pendingReasoningItemId, items.size() + 1,
                            new ConversationItem.Reasoning("", null))));
        }
        reasoningBuffer.append(text);
        emit(new ResponseEvent.ReasoningDelta(responseId, ++seq, pendingReasoningItemId, text));
    }

    private void onToken(String text) {
        flushReasoning();
        if (pendingTextItemId == null) {
            pendingTextItemId = nextItemId("msg");
            emit(new ResponseEvent.OutputItemAdded(responseId, ++seq,
                    new ConversationItemRecord(pendingTextItemId, items.size() + 1, ConversationItem.Message.assistant(""))));
        }
        textBuffer.append(text);
        emit(new ResponseEvent.OutputTextDelta(responseId, ++seq, pendingTextItemId, text));
    }

    private void onToolCall(String runtimeCallId, String toolName, Map<String, Object> arguments) {
        flushText();
        String callId = nextItemId("call");
        openToolCalls.put(runtimeCallId, callId);
        addItem(new ConversationItem.FunctionCall(callId, toolName,
                arguments == null ? Map.of() : arguments));
    }

    private void onToolResult(String runtimeCallId, String text, boolean failed) {
        String callId = runtimeCallId == null ? null : openToolCalls.remove(runtimeCallId);
        addItem(new ConversationItem.FunctionCallOutput(
                callId == null ? "call_unknown" : callId, text, failed));
    }

    private void onDone() {
        flushText();
        status = ResponseStatus.COMPLETED;
        completedAt = Instant.now();
        emit(new ResponseEvent.Completed(responseId, ++seq, usage));
    }

    /** Finalizes the pending reasoning as its own item — it always precedes what it led to. */
    private void flushReasoning() {
        if (pendingReasoningItemId == null) return;
        ConversationItemRecord entry = new ConversationItemRecord(pendingReasoningItemId, items.size() + 1,
                new ConversationItem.Reasoning(reasoningBuffer.toString(), null));
        items.add(entry);
        emit(new ResponseEvent.OutputItemDone(responseId, ++seq, entry));
        reasoningBuffer.setLength(0);
        pendingReasoningItemId = null;
    }

    /** Finalizes the pending assistant text as a message item. */
    private void flushText() {
        flushReasoning();
        if (textBuffer.isEmpty() && pendingTextItemId == null) return;
        String itemId = pendingTextItemId != null ? pendingTextItemId : nextItemId("msg");
        ConversationItemRecord entry = new ConversationItemRecord(itemId, items.size() + 1,
                ConversationItem.Message.assistant(textBuffer.toString()));
        items.add(entry);
        emit(new ResponseEvent.OutputItemDone(responseId, ++seq, entry));
        textBuffer.setLength(0);
        pendingTextItemId = null;
    }

    private void addItem(ConversationItem item) {
        ConversationItemRecord entry = new ConversationItemRecord(nextItemId("item"), items.size() + 1, item);
        items.add(entry);
        emit(new ResponseEvent.OutputItemAdded(responseId, ++seq, entry));
        emit(new ResponseEvent.OutputItemDone(responseId, ++seq, entry));
    }

    private String nextItemId(String prefix) {
        return prefix + "_" + responseId + "_" + (++itemCounter);
    }

    private void emit(ResponseEvent event) {
        events.add(event);
        for (SubscriberSlot slot : subscribers) {
            if (!slot.caughtUp) {
                // Still receiving its backlog — queue, so it arrives after.
                slot.queued.addLast(event);
                continue;
            }
            try {
                slot.consumer().accept(event);
            } catch (RuntimeException ignored) {
                // a broken subscriber must never break the run (observation plane)
            }
        }
    }
}
