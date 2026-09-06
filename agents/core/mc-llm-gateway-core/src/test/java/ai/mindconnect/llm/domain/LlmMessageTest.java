package ai.mindconnect.llm.domain;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The content-block shape of {@link LlmMessage}, and {@code content()} as the text of it. */
class LlmMessageTest {

    @Test
    void aTextMessageIsOneTextBlock() {
        LlmMessage m = LlmMessage.user("hello");

        assertThat(m.parts()).containsExactly(new LlmContent.Text("hello"));
        assertThat(m.content()).isEqualTo("hello");
        assertThat(m.hasMedia()).isFalse();
    }

    @Test
    void contentJoinsTheTextBlocksAndSkipsMedia() {
        LlmMessage m = LlmMessage.user(List.of(
                new LlmContent.Text("look:"),
                new LlmContent.Image("AAAA", "image/png"),
                new LlmContent.Text("what is it?")));

        assertThat(m.content()).isEqualTo("look:\nwhat is it?");
        assertThat(m.hasMedia()).isTrue();
    }

    @Test
    void aToolCallTurnHasNoContent() {
        LlmMessage m = LlmMessage.assistantWithToolCalls(
                List.of(new ToolCall("c1", "get_weather", Map.of("city", "Berlin"), null)));

        assertThat(m.parts()).isEmpty();
        assertThat(m.content()).isNull();
        assertThat(m.hasMedia()).isFalse();
    }

    @Test
    void nullTextIsNoBlock() {
        assertThat(LlmMessage.assistant(null).parts()).isEmpty();
        assertThat(LlmMessage.assistant(null).content()).isNull();
        assertThat(LlmMessage.tool("c1", "ok").content()).isEqualTo("ok");
        assertThat(LlmMessage.tool("c1", "ok").toolCallId()).isEqualTo("c1");
    }

    @Test
    void partsAreUnmodifiable() {
        LlmMessage m = LlmMessage.user("hello");
        assertThatThrownBy(() -> m.parts().add(new LlmContent.Text("more")))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void toStringNamesTheMediaButNotItsBytes() {
        LlmMessage m = LlmMessage.user(List.of(
                new LlmContent.Text("see"), new LlmContent.Image("QUFBQUFBQUFB", "image/png")));

        assertThat(m.toString()).contains("content=see", "media=Image").doesNotContain("QUFBQUFBQUFB");
    }
}
