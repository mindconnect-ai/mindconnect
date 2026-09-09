package ai.mindconnect.agent.responses;

import ai.mindconnect.agent.protocol.event.ResponseEvent;
import ai.mindconnect.agent.protocol.item.ConversationItemRecord;
import ai.mindconnect.agent.responses.wire.ResponseDto;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Protocol events as the frames an OpenAI client's stream parser expects.
 *
 * <p>The two models line up almost exactly, which is no coincidence — the
 * protocol's events were derived from these. What differs is spelling, and
 * two structural rules that a client's state machine relies on.
 *
 * <p><b>Lifecycle frames repeat the whole response.</b> The protocol sends a
 * status change and expects the reader to already hold the object; OpenAI
 * sends the object every time. Hence the supplier: the frame asks for the
 * current response as it is written, not before.
 *
 * <p><b>{@code output_index} is a position, not a constant.</b> A client
 * resolves a delta by looking up {@code response.output[output_index]} and
 * asserting it is the kind of item the delta belongs to. Sending 0 for
 * everything breaks the moment a response starts with anything other than
 * the assistant message — a tool call, say, which is the normal case for an
 * agent. Items are therefore numbered in the order they are added, and
 * deltas cite the index of the item they belong to.
 *
 * <p>One instance belongs to one response; it carries that response's item
 * numbering.
 */
public final class StreamEvents {

    /** What goes on the wire: the SSE event name, and its JSON body. */
    public record Frame(String event, Map<String, Object> data) { }

    /**
     * Whether this event ends the run. An OpenAI stream terminates when the
     * response reaches a terminal status — a client reads until the
     * connection closes, so leaving it open makes a finished response look
     * like one that is still thinking.
     */
    public static boolean isTerminal(ResponseEvent event) {
        return event instanceof ResponseEvent.Completed
                || event instanceof ResponseEvent.Incomplete
                || event instanceof ResponseEvent.Failed
                || event instanceof ResponseEvent.Cancelled;
    }

    private final Supplier<ResponseDto> currentResponse;
    private final ResponsesMapper mapper;

    /** Item id → its position in {@code output}, in the order added. */
    private final Map<String, Integer> indices = new LinkedHashMap<>();

    public StreamEvents(Supplier<ResponseDto> currentResponse, ResponsesMapper mapper) {
        this.currentResponse = currentResponse;
        this.mapper = mapper;
    }

    /**
     * @return the frame to write, or {@code null} for a protocol event with
     *         no counterpart — an extension event, or an item OpenAI has no
     *         shape for. Inventing a name would break a strict parser for no
     *         gain, and the item still arrives in the final response.
     */
    public Frame frameFor(ResponseEvent event) {
        return switch (event) {
            case ResponseEvent.Created e -> lifecycle("response.created", e.seq());
            case ResponseEvent.InProgress e -> lifecycle("response.in_progress", e.seq());
            case ResponseEvent.Completed e -> lifecycle("response.completed", e.seq());
            case ResponseEvent.Incomplete e -> lifecycle("response.incomplete", e.seq());
            case ResponseEvent.Failed e -> lifecycle("response.failed", e.seq());

            // OpenAI has no cancelled event; a cancelled run ends as failed
            // from a reader's point of view, and the response object it
            // carries states the real status.
            case ResponseEvent.Cancelled e -> lifecycle("response.failed", e.seq());

            case ResponseEvent.OutputItemAdded e ->
                    itemFrame("response.output_item.added", e.seq(), e.entry());
            case ResponseEvent.OutputItemDone e ->
                    itemFrame("response.output_item.done", e.seq(), e.entry());

            case ResponseEvent.OutputTextDelta e ->
                    delta("response.output_text.delta", e.seq(), e.itemId(), e.delta());
            case ResponseEvent.ArgumentsDelta e ->
                    delta("response.function_call_arguments.delta", e.seq(), e.itemId(), e.delta());
            case ResponseEvent.ReasoningDelta e ->
                    delta("response.reasoning_summary_text.delta", e.seq(), e.itemId(), e.delta());

            default -> null;
        };
    }

    private Frame lifecycle(String type, long seq) {
        Map<String, Object> data = base(type, seq);
        data.put("response", currentResponse.get());
        return new Frame(type, data);
    }

    private Frame itemFrame(String type, long seq, ConversationItemRecord entry) {
        ResponseDto.OutputItemDto item = mapper.toOutputItem(entry);
        if (item == null) {
            // A protocol item with no OpenAI shape. Numbering it anyway would
            // shift every later index away from what the response object
            // shows, which is the one thing the client must be able to trust.
            return null;
        }
        Map<String, Object> data = base(type, seq);
        data.put("output_index", indexOf(entry.id()));
        data.put("item", item);
        return new Frame(type, data);
    }

    private Frame delta(String type, long seq, String itemId, String delta) {
        Map<String, Object> data = base(type, seq);
        data.put("item_id", itemId);
        data.put("output_index", indexOf(itemId));
        data.put("content_index", 0);
        data.put("delta", delta);
        return new Frame(type, data);
    }

    /**
     * The item's position, assigned on first sight. A delta can arrive
     * before the {@code added} frame for its item, so this must mint an
     * index rather than assume one already exists.
     */
    private int indexOf(String itemId) {
        if (itemId == null) {
            return indices.size();
        }
        return indices.computeIfAbsent(itemId, id -> indices.size());
    }

    private Map<String, Object> base(String type, long seq) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("type", type);
        data.put("sequence_number", seq);
        return data;
    }
}
