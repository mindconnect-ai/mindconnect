package ai.mindconnect.agent.protocol.runtime;

import ai.mindconnect.agent.domain.StreamEvent;
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
    private ResponseStatus status = ResponseStatus.IN_PROGRESS;
    private ResponseError error;
    private Instant completedAt;

    private record SubscriberSlot(Consumer<ResponseEvent> consumer) { }

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
                Usage.ZERO, error, Map.copyOf(metadata), createdAt, completedAt);
    }

    public Subscription subscribe(long afterSeq, Consumer<ResponseEvent> consumer) {
        List<ResponseEvent> replay;
        SubscriberSlot slot = new SubscriberSlot(consumer);
        synchronized (this) {
            replay = events.stream().filter(e -> e.seq() > afterSeq).toList();
            subscribers.add(slot);
        }
        replay.forEach(consumer);
        return () -> subscribers.remove(slot);
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
        emit(new ResponseEvent.Completed(responseId, ++seq, Usage.ZERO));
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
            try {
                slot.consumer().accept(event);
            } catch (RuntimeException ignored) {
                // a broken subscriber must never break the run (observation plane)
            }
        }
    }
}
