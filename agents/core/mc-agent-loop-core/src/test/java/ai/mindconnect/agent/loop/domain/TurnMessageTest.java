package ai.mindconnect.agent.loop.domain;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** A message's content as typed parts, and the text every non-media reader wants. */
class TurnMessageTest {

    private static TurnMessage user(ContentPart... parts) {
        return new TurnMessage(null, null, TurnMessage.Type.USER, List.of(parts), List.of(), Map.of());
    }

    @Test
    void the_text_joins_the_text_parts_and_skips_media() {
        TurnMessage message = user(
                new ContentPart.Text("compare these"),
                ContentPart.Image.of(new ContentPart.MediaSource.Url("https://example.org/a.png")),
                new ContentPart.Document(new ContentPart.MediaSource.FileId("file-1"), "report.pdf"),
                new ContentPart.Text("and tell me the difference"));

        assertThat(message.text()).isEqualTo("compare these\nand tell me the difference");
        assertThat(message.content()).hasSize(4);
    }

    @Test
    void a_message_that_is_only_media_has_empty_text() {
        TurnMessage message = user(new ContentPart.Audio(
                new ContentPart.MediaSource.Inline("UklGRg==", "audio/wav")));

        assertThat(message.text()).isEmpty();
    }

    @Test
    void an_assistant_answer_and_a_tool_result_are_one_text_part() {
        assertThat(TurnMessage.assistant("hello").content())
                .containsExactly(new ContentPart.Text("hello"));
        assertThat(TurnMessage.toolResult("c1", "search", "found it", false).content())
                .containsExactly(new ContentPart.Text("found it"));
    }

    @Test
    void a_dispatch_marker_and_a_tool_call_request_have_no_content() {
        assertThat(TurnMessage.dispatched("c1").content()).isEmpty();
        assertThat(TurnMessage.toolCalls(List.of(new TurnMessage.ToolCall("c1", "search", Map.of()))).content())
                .isEmpty();
    }

    @Test
    void storing_and_extending_a_message_keep_its_parts() {
        ContentPart image = ContentPart.Image.of(new ContentPart.MediaSource.FileId("file-1"));
        TurnMessage stored = user(new ContentPart.Text("look"), image)
                .inTurn("turn-1")
                .stored("m1")
                .with("durationMs", 12L);

        assertThat(stored.id()).isEqualTo("m1");
        assertThat(stored.turnId()).isEqualTo("turn-1");
        assertThat(stored.content()).containsExactly(new ContentPart.Text("look"), image);
        assertThat(stored.metadata()).containsEntry("durationMs", 12L);
    }

    @Test
    void a_message_says_what_its_model_call_cost_and_others_say_nothing() {
        assertThat(TurnMessage.assistant("hello").withUsage(new Usage(120, 15)).usage())
                .contains(new Usage(120, 15));
        assertThat(TurnMessage.assistant("hello").usage()).isEmpty();
    }

    @Test
    void a_cost_read_back_from_a_json_store_is_still_a_cost() {
        // a store that keeps metadata as JSON hands back Integers for small longs
        TurnMessage reloaded = TurnMessage.assistant("hello").with("inputTokens", 120).with("outputTokens", 15);

        assertThat(reloaded.usage()).contains(new Usage(120, 15));
    }
}
