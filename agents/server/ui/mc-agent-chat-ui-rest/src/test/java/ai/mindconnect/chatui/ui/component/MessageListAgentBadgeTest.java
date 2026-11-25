package ai.mindconnect.chatui.ui.component;

import ai.mindconnect.agent.SessionId;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import ai.mindconnect.jackson.JsonMapperFactory;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A chat that changed anything of its agent runs on settings of its own and
 * no longer follows that agent; the header says so, whatever was changed.
 */
class MessageListAgentBadgeTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ai.mindconnect.agent.management.api.ManagedAgent AGENT =
            ai.mindconnect.chatui.ChatFixture.agent("coder", "", "", "cfg");

    @Test
    void aChatOnItsOwnSettingsSaysSoInTheHeader() throws Exception {
        String own = JSON.writeValueAsString(new MessageListComponent(new SessionId("s-1"), AGENT, List.of(), null, new JsonMapperFactory(List.of()).create())
                .withOwnSettings(true).render());
        String following = JSON.writeValueAsString(
                new MessageListComponent(new SessionId("s-1"), AGENT, List.of(), null, new JsonMapperFactory(List.of()).create()).render());

        assertThat(own).contains("own settings");
        assertThat(following).doesNotContain("own settings");
    }
}
