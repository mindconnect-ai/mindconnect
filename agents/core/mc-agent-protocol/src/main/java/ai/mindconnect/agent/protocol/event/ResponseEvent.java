package ai.mindconnect.agent.protocol.event;

import ai.mindconnect.agent.protocol.IncompleteReason;
import ai.mindconnect.agent.protocol.ResponseError;
import ai.mindconnect.agent.protocol.Usage;
import ai.mindconnect.agent.protocol.item.ConversationItemRecord;

import java.util.Map;


/**
 * Semantic stream events of one response. Every event carries the response id
 * and a per-response, strictly increasing {@code seq} — reconnecting clients
 * resume with {@code afterSeq} and can never miss an item, only delta events
 * (whose content is contained in the finished item anyway).
 *
 * <p>Almost all events are <b>projections</b> of transcript operations
 * (concept 7): appending an item yields {@code OutputItemAdded}/{@code Done},
 * status changes yield the lifecycle events. Only the delta events originate
 * elsewhere (the LLM adapter). Replaying a stream from {@code afterSeq=0}
 * re-renders the stored items — the stream is derived state, never the truth.
 *
 * <p>Streams are flat: a sub-agent's events live on the child response's own
 * stream (subscribe by the {@code AgentCall} item's {@code childResponseId}).
 * Aggregation over a tree is a subscription option, not an event structure.
 */
public sealed interface ResponseEvent {

    String responseId();

    long seq();

    // ── Lifecycle ───────────────────────────────────────────────────────────

    record Created(String responseId, long seq) implements ResponseEvent {}

    record InProgress(String responseId, long seq) implements ResponseEvent {}

    record Completed(String responseId, long seq, Usage usage) implements ResponseEvent {}

    record Incomplete(String responseId, long seq, IncompleteReason reason) implements ResponseEvent {}

    record Failed(String responseId, long seq, ResponseError error) implements ResponseEvent {}

    record Cancelled(String responseId, long seq) implements ResponseEvent {}

    // ── Output items ────────────────────────────────────────────────────────

    /**
     * A new output item exists. For streamed items (assistant text, function
     * call arguments) the envelope may still be partial — deltas follow, then
     * {@link OutputItemDone} with the final envelope.
     */
    record OutputItemAdded(String responseId, long seq, ConversationItemRecord entry) implements ResponseEvent {}

    /** The item is final. Always fired, also for items that never streamed. */
    record OutputItemDone(String responseId, long seq, ConversationItemRecord entry) implements ResponseEvent {}

    // ── Deltas (live only — absent from replays) ────────────────────────────

    /** A chunk of assistant message text for the item {@code itemId}. */
    record OutputTextDelta(String responseId, long seq, String itemId, String delta) implements ResponseEvent {}

    /** A chunk of streamed function-call arguments for the item {@code itemId}. */
    record ArgumentsDelta(String responseId, long seq, String itemId, String delta) implements ResponseEvent {}

    /** A chunk of reasoning text for the item {@code itemId}. */
    record ReasoningDelta(String responseId, long seq, String itemId, String delta) implements ResponseEvent {}

    // ── Extension ───────────────────────────────────────────────────────────

    /**
     * Something a RUNTIME did that this protocol has no word for — a response
     * reviewer running, a compaction starting, whatever comes next.
     *
     * <p>Same rule as {@code Response.metadata}, and for the same reasons:
     * extension arrives as DATA under a namespaced kind ({@code mc.*},
     * {@code openai.*}), never as a new subtype — a subtype would break sealed
     * exhaustiveness, serialization totality and backend neutrality. Neutral
     * clients ignore kinds they do not know; knowing clients read the ones they
     * do.
     *
     * <p>Ephemeral, like the deltas: it corresponds to no item, so a replay
     * from the store cannot show it again. Anything that must survive a
     * reconnect has to be an item.
     *
     * <p>Encode and decode it in ONE place per kind — a small typed record next
     * to the code that emits it — so the string never travels through callers.
     *
     * @param kind namespaced identifier, e.g. {@code mc.review.started}
     * @param data payload for that kind; keep it to simple JSON-able values
     */
    record Activity(String responseId, long seq, String kind, Map<String, Object> data)
            implements ResponseEvent {

        public Activity {
            data = data == null ? Map.of() : Map.copyOf(data);
        }
    }
}
