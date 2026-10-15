package ai.mindconnect.chatui.ui.page;

import ai.mindconnect.agent.AgentId;
import ai.mindconnect.agent.UserId;
import ai.mindconnect.agent.runtime.domain.AgentDefinition;
import ai.mindconnect.agent.runtime.domain.AgentSession;
import ai.mindconnect.chatui.ui.component.ChatFormComponent;
import ai.mindconnect.message.domain.ConversationId;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The typing bubble's life in one turn: it appears with the user's message,
 * at the bottom of the conversation, and every way the turn can go takes it
 * away again.
 */
class ChatPageTypingBubbleTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final AgentDefinition AGENT = new AgentDefinition(AgentId.random(), "coder", null, null, null, null,
            null, "cfg", 5, null, null, List.of(), List.of(), List.of(), null, null, null);
    private static final String TYPING = "bot-typing-s-1";

    private final AgentSession session = AgentSession.start(AGENT.id(), UserId.of("u"), ConversationId.random());
    private final ChatPage page = new ChatPage(session, AGENT, List.of(), null);

    private List<JsonNode> ops(Object patch) {
        var out = new ArrayList<JsonNode>();
        JSON.valueToTree(patch).path("patches").forEach(out::add);
        return out;
    }

    private static JsonNode op(List<JsonNode> ops, String op, String targetId) {
        return ops.stream()
                .filter(o -> op.equals(o.path("op").asText()) && targetId.equals(o.path("targetId").asText()))
                .findFirst().orElse(null);
    }

    private String scrollPane() {
        return "chat-scroll-" + session.id().value();
    }

    @Test
    void theBubbleComesWithTheUsersMessageAndSitsAfterTheList() {
        var ops = ops(page.streamStart("hello", TYPING));

        // After the list, not in it: cards appended to the list land above it.
        JsonNode append = op(ops, "APPEND", scrollPane());
        assertThat(append).as("the typing bubble goes into the scroll pane").isNotNull();
        JsonNode row = append.path("node");
        assertThat(row.path("id").asText()).isEqualTo(TYPING);
        assertThat(row.path("cssClass").asText()).isEqualTo("chat-typing-row");
        JsonNode bubble = row.path("children").get(0);
        assertThat(bubble.path("type").asText())
                .as("a spinner: role=status, named by its title")
                .isEqualTo("spinner");
        assertThat(bubble.path("title").asText()).isEqualTo("The assistant is typing");
        assertThat(bubble.path("cssClass").asText()).isEqualTo("chat-typing");
        assertThat(bubble.has("label")).as("dots only, no text").isFalse();
        assertThat(JSON.valueToTree(page.streamStart("hello", TYPING)).toString())
                .doesNotContain("AI is thinking");

        assertThat(op(ops, "MERGE", ChatFormComponent.formId(session.id())))
                .as("the composer switches without losing its textarea")
                .isNotNull();
    }

    @Test
    void aClientOpeningThePageMidTurnGetsTheBubbleOnItsOwn() {
        var ops = ops(page.streamTyping(TYPING));

        assertThat(ops).hasSize(1);
        assertThat(op(ops, "APPEND", scrollPane()).path("node").path("id").asText()).isEqualTo(TYPING);
    }

    @Test
    void theFirstTokenReplacesTheBubbleWithTheReply() {
        var ops = ops(page.streamFirstToken("bot-pending-1", TYPING));

        assertThat(op(ops, "REMOVE", TYPING)).isNotNull();
        assertThat(ops.indexOf(op(ops, "REMOVE", TYPING)))
                .as("gone before the reply bubble takes its place")
                .isZero();
        assertThat(ops.get(1).path("op").asText()).isEqualTo("APPEND");
    }

    /** A turn without a single token — tools only, a failure, Stop — still had the bubble. */
    @Test
    void everyEndOfTheTurnTakesTheBubbleAwayAndKeepsTheDraft() {
        for (var patch : new Object[]{page.streamDone(TYPING), page.streamError("boom", TYPING)}) {
            var ops = ops(patch);
            assertThat(op(ops, "REMOVE", TYPING)).isNotNull();
            assertThat(op(ops, "MERGE", ChatFormComponent.formId(session.id())))
                    .as("back to Send by MERGE, so the text typed during the turn stays")
                    .isNotNull();
            assertThat(op(ops, "REPLACE", ChatFormComponent.formId(session.id()))).isNull();
        }
    }
}
