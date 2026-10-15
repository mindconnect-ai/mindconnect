package ai.mindconnect.agent.protocol;

import ai.mindconnect.agent.protocol.item.ContentPart;
import ai.mindconnect.agent.protocol.item.ConversationItem;
import ai.mindconnect.agent.protocol.item.ConversationItemRecord;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ResponseOutputTextTest {

    @Test
    void returnsTextOfLastAssistantMessage() {
        Response r = completed(
                ConversationItem.Message.assistant("draft answer"),
                new ConversationItem.FunctionCall("c1", "search", Map.of()),
                new ConversationItem.FunctionCallOutput("c1", "result", false),
                ConversationItem.Message.assistant("final answer"));

        assertThat(r.outputText()).isEqualTo("final answer");
    }

    @Test
    void ignoresUserMessagesAndNonTextParts() {
        Response r = completed(
                ConversationItem.Message.user("question"),
                new ConversationItem.Message(ai.mindconnect.agent.protocol.item.Role.ASSISTANT, List.of(
                        new ContentPart.Text("see attached: "),
                        new ContentPart.Image(new ContentPart.MediaSource.FileId("file_1"),
                                ContentPart.Image.Detail.AUTO),
                        new ContentPart.Text("done"))));

        assertThat(r.outputText()).isEqualTo("see attached: done");
    }

    @Test
    void emptyWhenNoAssistantTextYet() {
        Response r = completed(
                ConversationItem.Message.user("restart the cluster"),
                new ConversationItem.ApprovalRequest("req1", "tool_approval", Map.of(), "resp_origin"));

        assertThat(r.outputText()).isEmpty();
    }

    private static Response completed(ConversationItem... items) {
        List<ConversationItemRecord> output = new java.util.ArrayList<>();
        for (int i = 0; i < items.length; i++) {
            output.add(new ConversationItemRecord("item-" + (i + 1), i + 1, items[i]));
        }
        return new Response("resp_1", "conv_1", "sess_1",
                "test-agent", ResponseStatus.COMPLETED, null, null, null,
                output, Usage.ZERO, null, Map.of(), Instant.EPOCH, Instant.EPOCH);
    }
}
