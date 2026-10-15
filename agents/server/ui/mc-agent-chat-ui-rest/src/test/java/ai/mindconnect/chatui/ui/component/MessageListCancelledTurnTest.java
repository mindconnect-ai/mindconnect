package ai.mindconnect.chatui.ui.component;

import ai.mindconnect.agent.AgentId;
import ai.mindconnect.agent.SessionId;
import ai.mindconnect.agent.runtime.domain.AgentDefinition;
import ai.mindconnect.message.domain.ConversationId;
import ai.mindconnect.message.domain.Message;
import ai.mindconnect.message.domain.MessageType;
import ai.mindconnect.message.domain.ParticipantType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A turn cancelled while its tool ran has a call and a failed stub result but
 * no answer; the next thing in the history is the user's next question.
 */
class MessageListCancelledTurnTest {

    private static final ConversationId CONVERSATION = new ConversationId(java.util.UUID.randomUUID().toString());
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final AgentDefinition AGENT = new AgentDefinition(AgentId.random(), "coder", null, null, null, null, null,
            "cfg", 5, null, null, List.of(), List.of(), List.of(), null, null, null);

    @Test
    void theToolCardOfACancelledTurnIsDrawnAboveTheNextQuestionAsFailed() throws Exception {
        Message question = chat(ParticipantType.USER, "Run sleep 25", 1);
        Message call = Message.of(CONVERSATION, "agent", ParticipantType.AGENT, MessageType.TOOL_CALL,
                "{\"toolCalls\":[{\"name\":\"bash\",\"arguments\":{\"command\":\"sleep 25\"},\"id\":\"call_1\"}]}", 2);
        Message stub = Message.of(CONVERSATION, "agent", ParticipantType.AGENT, MessageType.TOOL_RESULT,
                        "{\"toolCallId\":\"call_1\",\"result\":\"Cancelled by user before the tool finished\",\"toolName\":\"bash\"}", 3)
                .withMetadata(Map.of("callId", "call_1", "failed", true, "toolName", "bash"));
        Message followUp = chat(ParticipantType.USER, "What happened?", 4);
        Message answer = chat(ParticipantType.AGENT, "The command was cancelled.", 5);

        String page = JSON.writeValueAsString(new MessageListComponent(
                new SessionId("s-1"), AGENT, List.of(question, call, stub, followUp, answer), null).render());

        String cardId = "task-hist-" + stub.id().value();
        assertThat(page).contains(cardId);
        assertThat(page.indexOf(cardId)).isBetween(page.indexOf("Run sleep 25"), page.indexOf("What happened?"));
        assertThat(page).contains(TaskCardComponent.failedToolHeader("bash", 0));
        // Once, not again above the answer to the follow-up.
        assertThat(page.split(java.util.regex.Pattern.quote("\"" + cardId + "\""), -1)).hasSize(2);
    }

    private static Message chat(ParticipantType sender, String text, int seq) {
        return Message.of(CONVERSATION, sender == ParticipantType.USER ? "user" : "agent", sender,
                MessageType.CHAT, text, seq);
    }
}
