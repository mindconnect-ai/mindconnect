package ai.mindconnect.chatui.ui.component;

import ai.mindconnect.agent.AgentId;
import ai.mindconnect.agent.SessionId;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The composer while a turn runs: the textarea stays open, nothing starts a
 * second turn, and neither switch between the states touches what was typed.
 */
class ChatFormDuringTurnTest {

    private static final SessionId SESSION = SessionId.of("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
    private static final AgentId AGENT = AgentId.of("11111111-2222-3333-4444-555555555555");
    private static final ObjectMapper JSON = new ObjectMapper();

    private static JsonNode tree(Object node) {
        return JSON.valueToTree(node);
    }

    private static JsonNode action(JsonNode actions, String id) {
        for (JsonNode a : actions) {
            if (id.equals(a.path("id").asText())) return a;
        }
        return null;
    }

    @Test
    void theTextareaStaysOpenWhileATurnRuns() {
        JsonNode form = tree(new ChatFormComponent(SESSION, AGENT, true).render());

        assertThat(form.path("type").asText()).isEqualTo("form");
        assertThat(form.path("id").asText()).isEqualTo(ChatFormComponent.formId(SESSION));
        assertThat(form.path("cssClass").asText()).contains("chat-form--streaming");
        JsonNode field = form.path("fields").get(0);
        assertThat(field.path("id").asText()).isEqualTo("message");
        assertThat(field.path("fieldType").asText()).isEqualTo("TEXTAREA");
        assertThat(field.path("editable").asBoolean())
                .as("typing goes on while the reply is written")
                .isTrue();
    }

    /**
     * Enter still submits the form, and a submitted form fires its primary
     * action. That has to be a Send that cannot fire — without a primary the
     * form would pick its first action, Stop.
     */
    @Test
    void nothingInTheStreamingStateStartsASecondTurn() {
        JsonNode actions = tree(new ChatFormComponent(SESSION, AGENT, true).render()).path("actions");

        JsonNode send = action(actions, "send");
        assertThat(send).isNotNull();
        assertThat(send.path("style").asText()).isEqualTo("PRIMARY");
        assertThat(send.path("enabled").asBoolean()).isFalse();
        assertThat(send.has("onClick")).as("a disabled Send without a trigger makes Enter a no-op").isFalse();

        JsonNode stop = action(actions, "stop");
        assertThat(stop).isNotNull();
        assertThat(stop.path("style").asText()).isNotEqualTo("PRIMARY");
        assertThat(stop.path("onClick").path("url").asText())
                .isEqualTo("/chat/api/streams/msg-list-" + SESSION.value());
    }

    /**
     * A REPLACE keeps only the focused control's value; a MERGE that leaves
     * the fields out keeps the user's input whether it has focus or not. So
     * the text typed during a turn is still there after it.
     */
    @Test
    void bothSwitchesLeaveTheTypedTextAlone() {
        var form = new ChatFormComponent(SESSION, AGENT);

        for (var op : new Object[]{form.toStreaming(), form.toIdle()}) {
            JsonNode patch = tree(op);
            assertThat(patch.path("op").asText()).isEqualTo("MERGE");
            assertThat(patch.path("targetId").asText()).isEqualTo(ChatFormComponent.formId(SESSION));
            assertThat(patch.path("attributes").has("fields")).isFalse();
            assertThat(patch.path("attributes").has("actions")).isTrue();
            assertThat(patch.path("attributes").has("content"))
                    .as("named even when empty, or the other state's extras would stay")
                    .isTrue();
            for (String part : new String[]{"actions", "content"}) {
                patch.path("attributes").path(part).forEach(node -> assertThat(node.path("type").asText())
                        .as("a node without its type has no renderer on the client: " + node)
                        .isNotEmpty());
            }
        }

        JsonNode streaming = tree(form.toStreaming()).path("attributes");
        assertThat(streaming.path("cssClass").asText()).contains("chat-form--streaming");
        assertThat(streaming.path("content")).isEmpty();
        assertThat(action(streaming.path("actions"), "send").path("enabled").asBoolean()).isFalse();

        JsonNode idle = tree(form.toIdle()).path("attributes");
        assertThat(idle.path("cssClass").asText()).isEqualTo("chat-form");
        assertThat(idle.path("content")).isNotEmpty();
        assertThat(action(idle.path("actions"), "send").path("onClick").path("url").asText())
                .isEqualTo("/chat/api/sessions/" + SESSION.value() + "/chat/stream");
        assertThat(action(idle.path("actions"), "stop")).isNull();
    }

    /** The one switch that is meant to empty the field: after a non-streaming send. */
    @Test
    void resetStillEmptiesTheField() {
        JsonNode patch = tree(new ChatFormComponent(SESSION, AGENT).reset());

        assertThat(patch.path("op").asText()).isEqualTo("REPLACE");
        assertThat(patch.path("node").path("fields").get(0).has("value")).isFalse();
    }
}
