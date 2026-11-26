package ai.mindconnect.chatui.ui.component;

import ai.mindconnect.agent.SessionId;
import ai.mindconnect.agent.loop.domain.ContentPart;
import ai.mindconnect.agent.loop.domain.TurnMessage;
import ai.mindconnect.agent.management.api.ManagedMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import ai.mindconnect.jackson.JsonMapperFactory;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A turn cancelled while its tool ran has a call and a failed stub result but
 * no answer; the next thing in the history is the user's next question.
 */
class MessageListCancelledTurnTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ai.mindconnect.agent.management.api.ManagedAgent AGENT =
            ai.mindconnect.chatui.ChatFixture.agent("coder", "", "", "cfg");

    @Test
    void theToolCardOfACancelledTurnIsDrawnAboveTheNextQuestionAsFailed() throws Exception {
        ManagedMessage question = stored(1, TurnMessage.user(List.of(new ContentPart.Text("Run sleep 25"))));
        ManagedMessage call = stored(2, TurnMessage.toolCalls(List.of(
                new TurnMessage.ToolCall("call_1", "bash", Map.of("command", "sleep 25")))));
        ManagedMessage stub = stored(3, TurnMessage.toolResult("call_1", "bash",
                "Cancelled by user before the tool finished", true));
        ManagedMessage followUp = stored(4, TurnMessage.user(List.of(new ContentPart.Text("What happened?"))));
        ManagedMessage answer = stored(5, TurnMessage.assistant("The command was cancelled."));

        String page = JSON.writeValueAsString(new MessageListComponent(
                new SessionId("s-1"), AGENT, List.of(question, call, stub, followUp, answer), null, new JsonMapperFactory(List.of()).create()).render());

        String cardId = "task-hist-" + stub.id();
        assertThat(page).contains(cardId);
        assertThat(page.indexOf(cardId)).isBetween(page.indexOf("Run sleep 25"), page.indexOf("What happened?"));
        assertThat(page).contains(TaskCardComponent.failedToolHeader("bash", 0));
        // Once, not again above the answer to the follow-up.
        assertThat(page.split(java.util.regex.Pattern.quote("\"" + cardId + "\""), -1)).hasSize(2);
    }

    private static ManagedMessage stored(int sequence, TurnMessage message) {
        return new ManagedMessage(java.util.UUID.randomUUID().toString(), sequence, java.time.Instant.now(),
                message, null, null);
    }
}
