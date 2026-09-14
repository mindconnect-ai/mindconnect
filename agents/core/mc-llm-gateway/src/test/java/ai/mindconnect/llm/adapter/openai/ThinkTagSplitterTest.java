package ai.mindconnect.llm.adapter.openai;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Inline {@code <think>} tags cut out of a content stream, however the deltas fall. */
class ThinkTagSplitterTest {

    private final ThinkTagSplitter splitter = new ThinkTagSplitter();

    @Test
    void plainTextPassesThroughUntouched() {
        ThinkTagSplitter.Split s = splitter.feed("Hello, world");
        assertThat(s.text()).isEqualTo("Hello, world");
        assertThat(s.thinking()).isNull();
        assertThat(splitter.flush()).isEqualTo(ThinkTagSplitter.Split.NONE);
    }

    @Test
    void tagsInOneDeltaSplitThinkingFromText() {
        ThinkTagSplitter.Split s = splitter.feed("<think>let me see</think>The answer");
        assertThat(s.thinking()).isEqualTo("let me see");
        assertThat(s.text()).isEqualTo("The answer");
    }

    @Test
    void aTagCutAcrossDeltasIsStillRecognised() {
        assertThat(splitter.feed("<thi")).isEqualTo(ThinkTagSplitter.Split.NONE);
        ThinkTagSplitter.Split s = splitter.feed("nk>hmm");
        assertThat(s.thinking()).isEqualTo("hmm");
        assertThat(s.text()).isNull();
        s = splitter.feed(" more</th");
        assertThat(s.thinking()).isEqualTo(" more");
        s = splitter.feed("ink>done");
        assertThat(s.thinking()).isNull();
        assertThat(s.text()).isEqualTo("done");
    }

    @Test
    void aLoneAngleBracketIsReleasedOnceItCannotBeATag() {
        assertThat(splitter.feed("a < b")).isEqualTo(new ThinkTagSplitter.Split("a < b", null));
        assertThat(splitter.feed("x <")).isEqualTo(new ThinkTagSplitter.Split("x ", null));
        assertThat(splitter.feed("= y")).isEqualTo(new ThinkTagSplitter.Split("<= y", null));
    }

    @Test
    void flushReleasesWhatWasHeldBack() {
        splitter.feed("end <");
        assertThat(splitter.flush()).isEqualTo(new ThinkTagSplitter.Split("<", null));
    }

    @Test
    void aTagAfterRealTextIsJustText() {
        // The user asked what the tag does; the model quotes it.
        assertThat(splitter.feed("The tag looks like <think>this</think>, and it"))
                .isEqualTo(new ThinkTagSplitter.Split("The tag looks like <think>this, and it", null));
        assertThat(splitter.feed(" wraps <think>reasoning"))
                .isEqualTo(new ThinkTagSplitter.Split(" wraps <think>reasoning", null));
    }

    @Test
    void leadingWhitespaceDoesNotCountAsText() {
        assertThat(splitter.feed("\n")).isEqualTo(new ThinkTagSplitter.Split("\n", null));
        ThinkTagSplitter.Split s = splitter.feed("<think>hmm</think>ok");
        assertThat(s.thinking()).isEqualTo("hmm");
        assertThat(s.text()).isEqualTo("ok");
    }

    @Test
    void aStrayClosingTagIsDropped() {
        // The server's template pre-filled <think>; only the closing tag arrives.
        assertThat(splitter.feed("so 42</think>The answer is 42"))
                .isEqualTo(new ThinkTagSplitter.Split("so 42The answer is 42", null));
        assertThat(splitter.feed("x</th")).isEqualTo(new ThinkTagSplitter.Split("x", null));
        assertThat(splitter.feed("ink>y")).isEqualTo(new ThinkTagSplitter.Split("y", null));
    }

    @Test
    void anUnclosedThoughtFlushesAsThinking() {
        assertThat(splitter.feed("<think>never closed</"))
                .isEqualTo(new ThinkTagSplitter.Split(null, "never closed"));
        assertThat(splitter.flush()).isEqualTo(new ThinkTagSplitter.Split(null, "</"));
    }
}
