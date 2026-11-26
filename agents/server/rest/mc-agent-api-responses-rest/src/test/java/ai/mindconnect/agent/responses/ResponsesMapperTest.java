package ai.mindconnect.agent.responses;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import ai.mindconnect.agent.protocol.Response;
import ai.mindconnect.agent.protocol.ResponseStatus;
import ai.mindconnect.agent.protocol.item.ConversationItem;
import ai.mindconnect.agent.protocol.item.ConversationItemRecord;

/** A response on the wire shows what was delegated, also where the task text is not known. */
class ResponsesMapperTest {

    @Test
    void aSubAgentCallWithoutItsTaskTextIsStillShown() {
        Response response = new Response("resp_1", "c1", "s1", "default-chat", ResponseStatus.COMPLETED, null, null,
                null, List.of(new ConversationItemRecord("item_1", 1,
                        new ConversationItem.AgentCall("call_1", "explorer", null, null))),
                null, null, Map.of(), Instant.EPOCH, Instant.EPOCH);

        var dto = new ResponsesMapper(new ObjectMapper()).toDto(response, "default-chat");

        assertThat(new ObjectMapper().valueToTree(dto).toString())
                .contains("function_call", "run_agent", "explorer");
    }
}
