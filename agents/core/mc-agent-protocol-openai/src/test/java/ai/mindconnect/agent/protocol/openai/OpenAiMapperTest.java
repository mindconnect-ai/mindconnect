package ai.mindconnect.agent.protocol.openai;

import ai.mindconnect.agent.protocol.IncompleteReason;
import ai.mindconnect.agent.protocol.Response;
import ai.mindconnect.agent.protocol.ResponseStatus;
import ai.mindconnect.agent.protocol.event.ResponseEvent;
import ai.mindconnect.agent.protocol.item.ConversationItem;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Offline: maps captured OpenAI wire JSON — no network involved. */
class OpenAiMapperTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    @Test
    void completedResponseWithMessage() throws Exception {
        var node = JSON.readTree("""
                {"id":"resp_1","status":"completed","created_at":1723972000,
                 "model":"gpt-5-mini","conversation":{"id":"conv_1"},
                 "output":[
                   {"id":"ws_1","type":"web_search_call","status":"completed"},
                   {"id":"msg_1","type":"message","role":"assistant",
                    "content":[{"type":"output_text","text":"Lisbon is sunny."}]}],
                 "usage":{"input_tokens":100,"output_tokens":20}}
                """);

        Response r = OpenAiMapper.response(node, "sess_1", "assistant", Set.of());

        assertThat(r.status()).isEqualTo(ResponseStatus.COMPLETED);
        assertThat(r.outputText()).isEqualTo("Lisbon is sunny.");
        assertThat(r.usage().totalTokens()).isEqualTo(120);
        // provider specifics travel in the extension slot, never as a subtype:
        assertThat(r.metadata()).containsEntry("openai.model", "gpt-5-mini");
        // the hosted web_search call is visible but NOT open (name not declared)
        assertThat(r.output()).hasSize(2);
        assertThat(r.openFunctionCalls()).extracting(ConversationItem.FunctionCall::name)
                .containsExactly("web_search_call");   // open in the raw sense …
    }

    @Test
    void openDeclaredFunctionCallBecomesWaitingForToolOutput() throws Exception {
        var node = JSON.readTree("""
                {"id":"resp_2","status":"completed",
                 "output":[
                   {"id":"fc_1","type":"function_call","call_id":"call_1",
                    "name":"get_weather","arguments":"{\\"city\\":\\"Lisbon\\"}"}],
                 "usage":{"input_tokens":50,"output_tokens":10}}
                """);

        Response r = OpenAiMapper.response(node, "sess_1", "assistant", Set.of("get_weather"));

        // … but only DECLARED names flip the status:
        assertThat(r.status()).isEqualTo(ResponseStatus.INCOMPLETE);
        assertThat(r.incompleteReason()).isEqualTo(IncompleteReason.WAITING_FOR_TOOL_OUTPUT);
        ConversationItem.FunctionCall call = r.openFunctionCalls().get(0);
        assertThat(call.callId()).isEqualTo("call_1");
        assertThat(call.arguments()).containsEntry("city", "Lisbon");
    }

    @Test
    void hostedCallAloneStaysCompleted() throws Exception {
        var node = JSON.readTree("""
                {"id":"resp_3","status":"completed",
                 "output":[
                   {"id":"ws_1","type":"web_search_call","status":"completed"},
                   {"id":"msg_1","type":"message","role":"assistant",
                    "content":[{"type":"output_text","text":"done"}]}],
                 "usage":{}}
                """);

        Response r = OpenAiMapper.response(node, null, null, Set.of("get_weather"));

        assertThat(r.status()).isEqualTo(ResponseStatus.COMPLETED);
    }

    @Test
    void textDeltaEvent() throws Exception {
        var node = JSON.readTree("""
                {"type":"response.output_text.delta","sequence_number":7,
                 "item_id":"msg_1","delta":"Lis"}
                """);
        var event = OpenAiMapper.event(node, "resp_1").orElseThrow();

        assertThat(event).isInstanceOf(ResponseEvent.OutputTextDelta.class);
        var delta = (ResponseEvent.OutputTextDelta) event;
        assertThat(delta.seq()).isEqualTo(7);
        assertThat(delta.delta()).isEqualTo("Lis");
    }

    @Test
    void inputMessageSerializes() {
        var json = OpenAiMapper.inputItemJson(ConversationItem.Message.user("hello"));
        assertThat(json).containsEntry("type", "message").containsEntry("role", "user");
    }
}
