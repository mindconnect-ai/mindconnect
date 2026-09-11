package ai.mindconnect.agent.protocol.runtime;

import ai.mindconnect.agent.runtime.domain.StreamEvent;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Assembles the runtime's {@link StreamEvent}s into protocol items and
 * {@link ResponseEvent}s for ONE response. This is the whole translation —
 * pure state machine, no I/O, unit-testable without a runtime.
 *
 * <p>Buffered events replay to late subscribers ({@code afterSeq}); tokens
 * accumulate into the pending assistant message item, which is flushed when
 * a tool call, sub-agent call or the end of the turn interrupts the text.
 *
 * <p>Mapping notes: the runtime's stream events carry no tool-call ids, so
 * call ids are synthesized and results are paired FIFO (the runtime executes
 * a round's tools sequentially). Sub-agent turns are not addressable
 * responses on this backend (yet) — {@code AgentCall.childResponseId} carries
 * the sub-session id instead, and child events are folded away.
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
    private final Deque<String> openToolCalls = new ArrayDeque<>();
    private final Map<UUID, String> openAgentTasks = new HashMap<>();

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

    // ── StreamEvent translation ─────────────────────────────────────────────

    public synchronized void accept(StreamEvent event) {
        if (status.terminal()) return;
        switch (event) {
            case StreamEvent.Token t -> onToken(t.text());
            case StreamEvent.ToolCallStarted t -> onToolCall(t.toolName(), t.arguments());
            case StreamEvent.ToolCallResult t -> onToolResult(t.result(), false);
            case StreamEvent.ToolCallFailed t -> onToolResult(t.error(), true);
            case StreamEvent.SubAgentStarted t -> onSubAgentStarted(t);
            case StreamEvent.SubAgentDone t -> closeAgentTask(t.taskId(), t.finalText(), false);
            case StreamEvent.SubAgentError t -> closeAgentTask(t.taskId(), t.error(), true);
            case StreamEvent.ResponseRevised t -> {
                textBuffer.setLength(0);
                textBuffer.append(t.finalText());
            }
            case StreamEvent.TurnUsage t -> usage = new Usage(t.inputTokens(), t.outputTokens());
            case StreamEvent.Done t -> onDone();
            // folded away: nested sub-agent streams, status-only events
            case StreamEvent.SubAgentEvent t -> { }
            case StreamEvent.AskingLlm t -> { }
            case StreamEvent.Reviewing t -> { }
            case StreamEvent.ReviewerDecision t -> { }
            // Approval requests reach protocol clients as the persisted
            // APPROVAL_REQUEST item once the item mapping lands (K07); the
            // live event is a UI concern for now.
            case StreamEvent.ApprovalRequested t -> { }
        }
    }

    /** Terminal failure signalled by the turn's future rather than the stream. */
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
                status, null, null, null, List.copyOf(items),
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

    private void onToken(String text) {
        if (pendingTextItemId == null) {
            pendingTextItemId = nextItemId("msg");
            emit(new ResponseEvent.OutputItemAdded(responseId, ++seq,
                    new ConversationItemRecord(pendingTextItemId, items.size() + 1, ConversationItem.Message.assistant(""))));
        }
        textBuffer.append(text);
        emit(new ResponseEvent.OutputTextDelta(responseId, ++seq, pendingTextItemId, text));
    }

    private void onToolCall(String toolName, Map<String, Object> arguments) {
        flushText();
        String callId = nextItemId("call");
        openToolCalls.addLast(callId);
        addItem(new ConversationItem.FunctionCall(callId, toolName,
                arguments == null ? Map.of() : arguments));
    }

    private void onToolResult(String text, boolean failed) {
        String callId = openToolCalls.pollFirst();
        addItem(new ConversationItem.FunctionCallOutput(
                callId == null ? "call_unknown" : callId, text, failed));
    }

    private void onSubAgentStarted(StreamEvent.SubAgentStarted t) {
        flushText();
        String callId = nextItemId("task");
        openAgentTasks.put(t.taskId(), callId);
        addItem(new ConversationItem.AgentCall(callId, t.agentName(), t.input(),
                t.subSessionId() == null ? null : t.subSessionId().toString()));
    }

    private void closeAgentTask(UUID taskId, String text, boolean failed) {
        String callId = openAgentTasks.remove(taskId);
        addItem(new ConversationItem.FunctionCallOutput(
                callId == null ? "task_unknown" : callId,
                text == null ? "" : text, failed));
    }

    private void onDone() {
        flushText();
        status = ResponseStatus.COMPLETED;
        completedAt = Instant.now();
        emit(new ResponseEvent.Completed(responseId, ++seq, usage));
    }

    /** Finalizes the pending assistant text as a message item. */
    private void flushText() {
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
