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
}
