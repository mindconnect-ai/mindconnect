package ai.mindconnect.chatui.ui.component;

import ai.mindconnect.agent.SessionId;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The composer's "+" menu: the four things a conversation can be given, and
 * the routes behind them.
 *
 * <p>The entries are the point of the test. Files used to be the only one —
 * the "+" was a button that opened the attach dialog — and the tools and the
 * sub-agents were reachable only through a dialog named after the model. A
 * route lost here is a capability that quietly disappears from the composer.
 */
class ChatPlusMenuComponentTest {

    private static final String SESSION_VALUE = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";
    private static final SessionId SESSION = SessionId.of(SESSION_VALUE);

    private static String json(Object node) throws Exception {
        return new ObjectMapper().writeValueAsString(node);
    }

    private static String base() {
        return "/chat/api/sessions/" + SESSION_VALUE;
    }

    @Test
    void itOffersFilesImagesToolsAndSubAgents() throws Exception {
        String out = json(ChatPlusMenuComponent.menu(SESSION, 0, 0));

        // Files is the attach dialog's default, so it keeps the bare route.
        assertThat(out).contains("\"url\":\"" + base() + "/attach-dialog\"");
        assertThat(out).contains("\"url\":\"" + base() + "/attach-dialog?kind=images\"");
        assertThat(out).contains("\"url\":\"" + base() + "/tools-dialog\"");
        assertThat(out).contains("\"url\":\"" + base() + "/subagents-dialog\"");
    }

    /**
     * The popover opens rightwards. The "+" sits at the composer's left edge,
     * and an end-aligned menu would hang off the window on a phone.
     */
    @Test
    void theMenuOpensFromTheLeftEdge() throws Exception {
        assertThat(json(ChatPlusMenuComponent.menu(SESSION, 0, 0))).contains("\"align\":\"START\"");
    }

    /** A count of zero is not a badge: an empty "0" on a fresh chat is noise. */
    @Test
    void countsShowOnlyOnceThereIsSomethingToCount() throws Exception {
        assertThat(json(ChatPlusMenuComponent.menu(SESSION, 0, 0)))
                .doesNotContain("\"badge\"");

        String out = json(ChatPlusMenuComponent.menu(SESSION, 2, 7));
        assertThat(out).contains("\"badge\":\"2\"");
        assertThat(out).contains("\"badge\":\"7\"");
    }
}
