package ai.mindconnect.agent.responses;

import ai.mindconnect.agent.protocol.event.ResponseEvent;
import ai.mindconnect.agent.protocol.item.ConversationItem;
import ai.mindconnect.agent.protocol.item.ConversationItemRecord;
import ai.mindconnect.agent.responses.wire.ResponseDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The frames an OpenAI client's state machine reads, offline. */
class StreamEventsTest {

    private final ResponsesMapper mapper = new ResponsesMapper(new ObjectMapper());
    private final StreamEvents events = new StreamEvents(() -> null, mapper);

    private static ConversationItemRecord message(String id, String text) {
        return new ConversationItemRecord(id, 1, ConversationItem.Message.assistant(text));
    }

    private static ResponseDto.ContentPartDto part(StreamEvents.Frame frame) {
        return (ResponseDto.ContentPartDto) frame.data().get("part");
    }

    private static ResponseDto.OutputItemDto item(StreamEvents.Frame frame) {
        return (ResponseDto.OutputItemDto) frame.data().get("item");
    }

    private List<StreamEvents.Frame> stream(ResponseEvent... protocolEvents) {
        List<StreamEvents.Frame> frames = new ArrayList<>();
        for (ResponseEvent e : protocolEvents) {
            frames.addAll(events.framesFor(e));
        }
        return frames;
    }

    /**
     * Without the content-part markers a reader cannot tell that the deltas
     * and the text in the finished item are one content part — and one that
     * assumes otherwise renders the answer twice.
     */
    @Test
    void textItemIsWrappedInItsContentPart() {
        List<StreamEvents.Frame> frames = stream(
                new ResponseEvent.OutputItemAdded("resp_1", 1, message("msg_1", "")),
                new ResponseEvent.OutputTextDelta("resp_1", 2, "msg_1", "It is "),
                new ResponseEvent.OutputTextDelta("resp_1", 3, "msg_1", "sunny."),
                new ResponseEvent.OutputItemDone("resp_1", 4, message("msg_1", "It is sunny.")));

        assertThat(frames).extracting(StreamEvents.Frame::event).containsExactly(
                "response.output_item.added",
                "response.content_part.added",
                "response.output_text.delta",
                "response.output_text.delta",
                "response.output_text.done",
                "response.content_part.done",
                "response.output_item.done");

        assertThat(frames.get(4).data()).containsEntry("text", "It is sunny.");
        // the part opens empty and closes with the whole text: the deltas fill
        // it, so announcing the text up front would state the answer before it
        // was streamed, and a reader would have it twice
        assertThat(part(frames.get(1)).text()).isEmpty();
        assertThat(part(frames.get(5)).text()).isEqualTo("It is sunny.");
        // the item opens in_progress with nothing in it, and closes complete
        assertThat(item(frames.get(0)).status()).isEqualTo("in_progress");
        assertThat(item(frames.get(0)).content()).isEmpty();
        assertThat(item(frames.get(6)).content()).hasSize(1);
    }

    /** Every frame this layer writes is numbered, contiguously, from one. */
    @Test
    void sequenceNumbersAreContiguousAcrossExpandedFrames() {
        List<StreamEvents.Frame> frames = stream(
                new ResponseEvent.OutputItemAdded("resp_1", 7, message("msg_1", "")),
                new ResponseEvent.OutputTextDelta("resp_1", 9, "msg_1", "hi"),
                new ResponseEvent.OutputItemDone("resp_1", 40, message("msg_1", "hi")));

        List<Object> seqs = frames.stream().map(f -> f.data().get("sequence_number")).toList();
        assertThat(seqs).containsExactly(1L, 2L, 3L, 4L, 5L, 6L);
    }

    /**
     * The runtime hands a tool call over complete, so its arguments never
     * streamed — but a client waiting for the closing frame before it
     * dispatches would otherwise wait forever.
     */
    @Test
    void toolCallStillGetsItsArgumentsDoneFrame() {
        ConversationItemRecord call = new ConversationItemRecord("call_1", 1,
                new ConversationItem.FunctionCall("call_1", "web_search", Map.of("q", "lisbon")));

        List<StreamEvents.Frame> frames = stream(
                new ResponseEvent.OutputItemAdded("resp_1", 1, call),
                new ResponseEvent.OutputItemDone("resp_1", 2, call));

        assertThat(frames).extracting(StreamEvents.Frame::event).containsExactly(
                "response.output_item.added",
                "response.function_call_arguments.done",
                "response.output_item.done");
        assertThat(frames.get(1).data().get("arguments").toString()).contains("lisbon");
    }

    /**
     * The runtime closes an item it never opened when text reaches it
     * finished rather than as tokens — a reviewer's replacement answer. The
     * frames must still announce it: a reader resolves them against
     * {@code response.output[output_index]}, and the official SDK aborts the
     * whole stream on the entry that was never added.
     */
    @Test
    void anItemThatClosesWithoutOpeningIsAnnouncedFirst() {
        List<StreamEvents.Frame> frames = stream(
                new ResponseEvent.OutputItemDone("resp_1", 3, message("msg_1", "reviewed answer")));

        assertThat(frames).extracting(StreamEvents.Frame::event).containsExactly(
                "response.output_item.added",
                "response.content_part.added",
                "response.output_text.done",
                "response.content_part.done",
                "response.output_item.done");
        assertThat(frames).allSatisfy(f -> assertThat(f.data()).containsEntry("output_index", 0));
        assertThat(part(frames.get(1)).text()).isEmpty();
        assertThat(frames.get(2).data()).containsEntry("text", "reviewed answer");
    }

    /** An item announced once is not announced again when it closes. */
    @Test
    void anItemThatOpenedNormallyIsNotAnnouncedTwice() {
        List<StreamEvents.Frame> frames = stream(
                new ResponseEvent.OutputItemAdded("resp_1", 1, message("msg_1", "")),
                new ResponseEvent.OutputItemDone("resp_1", 2, message("msg_1", "hi")));

        assertThat(frames).extracting(StreamEvents.Frame::event)
                .filteredOn("response.output_item.added"::equals).hasSize(1);
    }

    /** Items are numbered by the order they are added, deltas cite their own. */
    @Test
    void outputIndexFollowsTheOrderItemsWereAdded() {
        ConversationItemRecord call = new ConversationItemRecord("call_1", 1,
                new ConversationItem.FunctionCall("call_1", "web_search", Map.of()));

        List<StreamEvents.Frame> frames = stream(
                new ResponseEvent.OutputItemAdded("resp_1", 1, call),
                new ResponseEvent.OutputItemAdded("resp_1", 2, message("msg_1", "")),
                new ResponseEvent.OutputTextDelta("resp_1", 3, "msg_1", "hi"));

        assertThat(frames.get(0).data()).containsEntry("output_index", 0);   // the tool call
        assertThat(frames.get(1).data()).containsEntry("output_index", 1);   // the message
        assertThat(frames.get(3).data()).containsEntry("output_index", 1);   // its delta
    }
}
