package ai.mindconnect.agent.responses;

import ai.mindconnect.agent.protocol.event.ResponseEvent;
import ai.mindconnect.agent.protocol.item.ConversationItemRecord;
import ai.mindconnect.agent.responses.wire.ResponseDto;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Protocol events as the frames an OpenAI client's stream parser expects.
 *
 * <p>The two models line up almost exactly, which is no coincidence — the
 * protocol's events were derived from these. What differs is spelling, and
 * three structural rules that a client's state machine relies on.
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
 * <p><b>Text arrives inside a content part, and the part is announced and
 * closed.</b> One protocol event can therefore be several frames: an item
 * that opens sends {@code output_item.added} and {@code content_part.added},
 * one that closes sends {@code output_text.done}, {@code content_part.done}
 * and {@code output_item.done}. Without those markers a reader cannot tell
 * that the delta stream and the text in the finished item are the same
 * content — and one that assumes they are not renders the answer twice.
 *
 * <p>Because a protocol event maps to zero, one or several frames, the
 * {@code sequence_number} on the wire is minted here rather than copied from
 * the protocol: a client checks it for gaps, and the protocol's own numbering
 * counts events this layer never sends.
 *
 * <p>One instance belongs to one response; it carries that response's item
 * numbering and its frame counter.
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

    /** Frames actually written, which is what {@code sequence_number} counts. */
    private long seq = 0;

    public StreamEvents(Supplier<ResponseDto> currentResponse, ResponsesMapper mapper) {
        this.currentResponse = currentResponse;
        this.mapper = mapper;
    }

    /**
     * @return the frames to write, in order — empty for a protocol event with
     *         no counterpart: an extension event, or an item OpenAI has no
     *         shape for. Inventing a name would break a strict parser for no
     *         gain, and the item still arrives in the final response.
     */
    public List<Frame> framesFor(ResponseEvent event) {
        return switch (event) {
            case ResponseEvent.Created e -> List.of(lifecycle("response.created"));
            case ResponseEvent.InProgress e -> List.of(lifecycle("response.in_progress"));
            case ResponseEvent.Completed e -> List.of(lifecycle("response.completed"));
            case ResponseEvent.Incomplete e -> List.of(lifecycle("response.incomplete"));
            case ResponseEvent.Failed e -> List.of(lifecycle("response.failed"));

            // OpenAI has no cancelled event; a cancelled run ends as failed
            // from a reader's point of view, and the response object it
            // carries states the real status.
            case ResponseEvent.Cancelled e -> List.of(lifecycle("response.failed"));

            case ResponseEvent.OutputItemAdded e -> itemAdded(e.entry());
            case ResponseEvent.OutputItemDone e -> itemDone(e.entry());

            case ResponseEvent.OutputTextDelta e ->
                    List.of(delta("response.output_text.delta", e.itemId(), e.delta()));
            case ResponseEvent.ArgumentsDelta e ->
                    List.of(delta("response.function_call_arguments.delta", e.itemId(), e.delta()));
            case ResponseEvent.ReasoningDelta e ->
                    List.of(delta("response.reasoning_summary_text.delta", e.itemId(), e.delta()));

            default -> List.of();
        };
    }

    // ── item lifecycle ──────────────────────────────────────────────────────

    /**
     * An item opens. A text-carrying item also opens its content part, empty:
     * the deltas that follow fill it, so announcing it with the text already
     * in place would state the answer before it was streamed.
     */
    private List<Frame> itemAdded(ConversationItemRecord entry) {
        ResponseDto.OutputItemDto item = mapper.toOutputItem(entry);
        if (item == null) {
            // A protocol item with no OpenAI shape. Numbering it anyway would
            // shift every later index away from what the response object
            // shows, which is the one thing the client must be able to trust.
            return List.of();
        }
        int index = indexOf(entry.id());
        List<Frame> frames = new ArrayList<>();
        frames.add(itemFrame("response.output_item.added", index, opening(item)));
        if (carriesText(item)) {
            Map<String, Object> data = base("response.content_part.added");
            data.put("item_id", item.id());
            data.put("output_index", index);
            data.put("content_index", 0);
            data.put("part", ResponseDto.ContentPartDto.outputText(""));
            frames.add(new Frame("response.content_part.added", data));
        }
        return frames;
    }

    /**
     * An item closes. The text ones close their content part first, which is
     * where a reader learns the final text belongs to the deltas it already
     * has rather than being a second copy to append.
     */
    private List<Frame> itemDone(ConversationItemRecord entry) {
        ResponseDto.OutputItemDto item = mapper.toOutputItem(entry);
        if (item == null) {
            return List.of();
        }
        int index = indexOf(entry.id());
        List<Frame> frames = new ArrayList<>();
        if (carriesText(item)) {
            String text = textOf(item);
            Map<String, Object> textDone = base("response.output_text.done");
            textDone.put("item_id", item.id());
            textDone.put("output_index", index);
            textDone.put("content_index", 0);
            textDone.put("text", text);
            frames.add(new Frame("response.output_text.done", textDone));

            Map<String, Object> partDone = base("response.content_part.done");
            partDone.put("item_id", item.id());
            partDone.put("output_index", index);
            partDone.put("content_index", 0);
            partDone.put("part", ResponseDto.ContentPartDto.outputText(text));
            frames.add(new Frame("response.content_part.done", partDone));
        } else if ("function_call".equals(item.type())) {
            // The runtime hands a tool call over complete, so the arguments
            // never streamed. The closing frame is still owed: a client that
            // waits for it before dispatching would otherwise wait forever.
            Map<String, Object> argsDone = base("response.function_call_arguments.done");
            argsDone.put("item_id", item.id());
            argsDone.put("output_index", index);
            argsDone.put("arguments", item.arguments() == null ? "" : item.arguments());
            frames.add(new Frame("response.function_call_arguments.done", argsDone));
        }
        frames.add(itemFrame("response.output_item.done", index, item));
        return frames;
    }

    // ── frame construction ──────────────────────────────────────────────────

    private Frame lifecycle(String type) {
        Map<String, Object> data = base(type);
        data.put("response", currentResponse.get());
        return new Frame(type, data);
    }

    private Frame itemFrame(String type, int index, ResponseDto.OutputItemDto item) {
        Map<String, Object> data = base(type);
        data.put("output_index", index);
        data.put("item", item);
        return new Frame(type, data);
    }

    private Frame delta(String type, String itemId, String delta) {
        Map<String, Object> data = base(type);
        data.put("item_id", itemId);
        data.put("output_index", indexOf(itemId));
        data.put("content_index", 0);
        data.put("delta", delta);
        return new Frame(type, data);
    }

    /** An item as it looks when it opens: still running, nothing in it yet. */
    private static ResponseDto.OutputItemDto opening(ResponseDto.OutputItemDto item) {
        if (!carriesText(item)) {
            return item;
        }
        return new ResponseDto.OutputItemDto(item.id(), item.type(), "in_progress", item.role(),
                List.of(), item.callId(), item.name(), item.arguments(), item.output(), item.summary());
    }

    private static boolean carriesText(ResponseDto.OutputItemDto item) {
        return "message".equals(item.type());
    }

    private static String textOf(ResponseDto.OutputItemDto item) {
        if (item.content() == null) {
            return "";
        }
        StringBuilder text = new StringBuilder();
        for (ResponseDto.ContentPartDto part : item.content()) {
            if (part.text() != null) {
                text.append(part.text());
            }
        }
        return text.toString();
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

    private Map<String, Object> base(String type) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("type", type);
        data.put("sequence_number", ++seq);
        return data;
    }
}
