package ai.mindconnect.chatui.ui.component;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An agent's reply quoted JSX outside a code block, the renderer turned it
 * into real, unclosed elements, and the conversation's layout came apart.
 * Text from a conversation never becomes DOM; code keeps rendering as code.
 */
class MarkdownTextTest {

    @Test
    void aTagOutsideCodeIsEscaped() {
        assertThat(MarkdownText.safe("Card: <div className=\"card\"><Text strong>API</Text>"))
                .isEqualTo("Card: &lt;div className=\"card\">&lt;Text strong>API&lt;/Text>");
        assertThat(MarkdownText.safe("<img src=x onerror=alert(1)>"))
                .startsWith("&lt;img");
    }

    @Test
    void codeIsLeftAsItIs() {
        String fenced = "Before <b>\n```tsx\n<div className=\"a\" />\n```\nafter <i>";
        assertThat(MarkdownText.safe(fenced))
                .isEqualTo("Before &lt;b>\n```tsx\n<div className=\"a\" />\n```\nafter &lt;i>");

        assertThat(MarkdownText.safe("use `<Modal open>` here, not <Modal>"))
                .isEqualTo("use `<Modal open>` here, not &lt;Modal>");
        assertThat(MarkdownText.safe("a ``code with ` and <x>`` b <y>"))
                .isEqualTo("a ``code with ` and <x>`` b &lt;y>");
    }

    @Test
    void aFenceClosesOnlyWithItsOwnKindAndLength() {
        String md = "~~~~\n<a>\n```\n<b>\n~~~~\n<c>";
        assertThat(MarkdownText.safe(md)).isEqualTo("~~~~\n<a>\n```\n<b>\n~~~~\n&lt;c>");
    }

    /** A reply still streaming may have opened a fence it has not closed yet — the renderer treats the rest as code too. */
    @Test
    void anUnclosedFenceRunsToTheEnd() {
        assertThat(MarkdownText.safe("text <p>\n```html\n<div>")).isEqualTo("text &lt;p>\n```html\n<div>");
    }

    @Test
    void autolinksKeepWorking() {
        assertThat(MarkdownText.safe("see <https://example.com/a?b=1> or <mailto:x@y.z>, not <b>"))
                .isEqualTo("see <https://example.com/a?b=1> or <mailto:x@y.z>, not &lt;b>");
    }

    @Test
    void textWithoutAnAngleBracketIsUntouched() {
        String md = "**bold** and `code` > quote";
        assertThat(MarkdownText.safe(md)).isSameAs(md);
        assertThat(MarkdownText.safe(null)).isNull();
    }

    /** Tool output that itself contains a fence must not close the card's code block and leak what follows. */
    @Test
    void aFenceInsideTheTextCannotCloseTheBlock() {
        String output = "# README\n```\n<div>\n```\n";
        String block = MarkdownText.fenced(output);
        assertThat(block).startsWith("````\n").endsWith("\n````");

        String body = TaskCardComponent.taskCardBody("{}", output);
        assertThat(body).contains("````\n# README\n```\n<div>\n```\n````");
    }

    @Test
    void theUserBubbleEscapesTheTextButKeepsItsOwnIcons() {
        String bubble = MessageComponent.userBubble(ai.mindconnect.agent.SessionId.of("s"),
                java.util.List.of("a<b>.png"), java.util.List.of(), "look at <div>", null);
        assertThat(bubble).contains("<svg class=\"sui-icon\"")
                .contains("a&lt;b>.png")
                .contains("look at &lt;div>");
    }

    @Test
    void aThoughtIsEscapedToo() throws Exception {
        String json = new com.fasterxml.jackson.databind.ObjectMapper()
                .writeValueAsString(TaskCardComponent.thinkingBody("n", "maybe <section> works"));
        assertThat(json).contains("maybe &lt;section> works");
    }

    // ── Where the escaper and marked used to disagree ──────────────────────
    // Each of these let a tag through: the escaper took a stretch for code that
    // marked renders as markdown, so nothing was escaped and the tag ran.

    /** An escaped backtick is a plain character to marked, not the start of a code span. */
    @Test
    void anEscapedBacktickDoesNotOpenACodeSpan() {
        assertThat(MarkdownText.safe("\\`<img src=x onerror=alert(1)>\\`"))
                .isEqualTo("\\`&lt;img src=x onerror=alert(1)>\\`");
    }

    /** Four spaces make an indented code block, not a fence — the line after it is markdown again. */
    @Test
    void aFenceIndentedFourSpacesIsNoFence() {
        assertThat(MarkdownText.safe("    ```\n<img src=x onerror=alert(1)>"))
                .isEqualTo("    ```\n&lt;img src=x onerror=alert(1)>");
        assertThat(MarkdownText.safe("\t```\n<img src=x onerror=alert(1)>"))
                .endsWith("&lt;img src=x onerror=alert(1)>");
        assertThat(MarkdownText.safe("   ```\n<b>\n   ```"))
                .isEqualTo("   ```\n<b>\n   ```");
    }

    /** marked closes a fence with the opening run followed by more backticks or tildes; after it comes markdown. */
    @Test
    void aFenceClosesByMarkedsRule() {
        assertThat(MarkdownText.safe("```\n<a>\n```~\n<img src=x onerror=alert(1)>"))
                .isEqualTo("```\n<a>\n```~\n&lt;img src=x onerror=alert(1)>");
        assertThat(MarkdownText.safe("````\n<a>\n```\n<b>\n````\n<c>"))
                .isEqualTo("````\n<a>\n```\n<b>\n````\n&lt;c>");
    }

    /** A CRLF fence is still a fence: its code is left alone, and what follows it is escaped. */
    @Test
    void crlfLineEndingsKeepTheFence() {
        assertThat(MarkdownText.safe("```\r\n<div>\r\n```\r\n<p>"))
                .isEqualTo("```\r\n<div>\r\n```\r\n&lt;p>");
    }

    /**
     * marked lets a code span run over a single line break. Paired line by line,
     * the closing backtick on the second line opened a "span" that swallowed the
     * tag after it — which marked renders as HTML.
     */
    @Test
    void aCodeSpanOverALineBreakPairsLikeMarkedDoes() {
        assertThat(MarkdownText.safe("`a\nb` <img src=x onerror=alert(1)> `c`"))
                .isEqualTo("`a\nb` &lt;img src=x onerror=alert(1)> `c`");
        assertThat(MarkdownText.safe("text `a\r\nb` <img src=x onerror=alert(1)> `<c>`"))
                .isEqualTo("text `a\r\nb` &lt;img src=x onerror=alert(1)> `<c>`");
    }

    /** A blank line ends the paragraph: a backtick before it does not pair with one after it. */
    @Test
    void aCodeSpanDoesNotRunOverABlankLine() {
        assertThat(MarkdownText.safe("`a\n\nb <img src=x onerror=alert(1)> `c`"))
                .isEqualTo("`a\n\nb &lt;img src=x onerror=alert(1)> `c`");
    }

    /** A heading is a block of its own; a code span cannot start in it and end in the next line. */
    @Test
    void aCodeSpanDoesNotRunOutOfAHeading() {
        assertThat(MarkdownText.safe("# `\n` a ` <img src=x onerror=alert(1)> `"))
                .isEqualTo("# `\n` a ` &lt;img src=x onerror=alert(1)> `");
        assertThat(MarkdownText.safe("# x `\n<img src=x onerror=alert(1)> `"))
                .isEqualTo("# x `\n&lt;img src=x onerror=alert(1)> `");
    }

    /** A span that runs over a line break keeps its tags escaped anyway — at worst they show as {@code &lt;}. */
    @Test
    void aMultiLineCodeSpanIsEscapedInside() {
        assertThat(MarkdownText.safe("`<a\nb>`")).isEqualTo("`&lt;a\nb>`");
    }

    /** A table splits a row into cells before it looks for code spans: a span never pairs across cells. */
    @Test
    void aTableRowIsEscapedCellByCell() {
        String table = "| a | b |\n| --- | --- |\n| `x | ` a ` <img src=x onerror=alert(1)> ` |";
        assertThat(MarkdownText.safe(table))
                .isEqualTo("| a | b |\n| --- | --- |\n| `x | ` a ` &lt;img src=x onerror=alert(1)> ` |");
        assertThat(MarkdownText.safe("| `<b>` |\n|---|\n| `<i>` |"))
                .isEqualTo("| `<b>` |\n|---|\n| `<i>` |");
    }

    /** A document's name is the user's (or a tool's) text too. */
    @Test
    void attachmentNamesInTheBubbleAreEscaped() {
        String bubble = MessageComponent.userBubble(ai.mindconnect.agent.SessionId.of("s"),
                java.util.List.of(), java.util.List.of(), "see file",
                java.util.List.of(new ai.mindconnect.message.domain.ContentPart.File(
                        "f-1", "<img src=x onerror=alert(1)>.pdf", "application/pdf", 10)));
        assertThat(bubble).contains("&lt;img src=x onerror=alert").doesNotContain("<img");
    }
}
