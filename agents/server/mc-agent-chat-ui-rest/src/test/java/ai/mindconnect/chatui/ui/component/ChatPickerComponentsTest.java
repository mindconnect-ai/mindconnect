package ai.mindconnect.chatui.ui.component;

import ai.mindconnect.agent.SessionId;
import ai.mindconnect.agent.runtime.domain.AgentDefinition;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The two pickers behind the "+": tools and sub-agents.
 *
 * <p>Both are rows with a switch rather than a form with an Apply, so what is
 * pinned here is the pair of things that makes that work — the toggle points
 * at the opposite of the current state, and the redraw target id stays what
 * the controller patches.
 */
class ChatPickerComponentsTest {

    private static final String SESSION_VALUE = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";
    private static final SessionId SESSION = SessionId.of(SESSION_VALUE);

    private static String json(Object node) throws Exception {
        return new ObjectMapper().writeValueAsString(node);
    }

    private static String base() {
        return "/chat/api/sessions/" + SESSION_VALUE;
    }

    private static String toolsPicker(Set<String> active, boolean search) throws Exception {
        return json(ChatToolsPickerComponent.node(SESSION,
                Map.of("files", List.of("read_file", "bash"), "web", List.of("web_search")),
                active, Map.of("web_search", "brave"), search));
    }

    /** A switch says what it will do next, not what it did: on → turn off. */
    @Test
    void aToolsSwitchPointsAtTheOppositeOfWhatIsOn() throws Exception {
        String out = toolsPicker(Set.of("read_file"), false);

        assertThat(out).contains("\"url\":\"" + base() + "/tools?tool=read_file&on=false\"");
        assertThat(out).contains("\"url\":\"" + base() + "/tools?tool=bash&on=true\"");
        assertThat(out).contains("\"url\":\"" + base() + "/tool-search?on=true\"");
    }

    @Test
    void toolSearchOffersTurningItOffOnceItIsOn() throws Exception {
        assertThat(toolsPicker(Set.of(), true))
                .contains("\"url\":\"" + base() + "/tool-search?on=false\"");
    }

    /**
     * A group opens when something in it is on, so a chat's actual reach is
     * readable without a click; the rest stay shut, because a picker of sixty
     * rows is not a picker.
     */
    @Test
    void onlyTheGroupsWithSomethingOnStartOpen() throws Exception {
        String out = toolsPicker(Set.of("web_search"), false);

        assertThat(out)
                .contains("\"collapseSummary\":\"Web  ·  1 of 1\"")
                .contains("\"collapseSummary\":\"Files  ·  0 of 2\"")
                // One of the two opens, the other does not.
                .contains("\"collapseOpen\":true")
                .contains("\"collapseOpen\":false");
    }

    /** Where a tool comes from is worth a line when the registry knows it. */
    @Test
    void aToolNamesItsSource() throws Exception {
        assertThat(toolsPicker(Set.of(), false)).contains("\"description\":\"from brave\"");
    }

    /** The body id is what a toggle REPLACEs — losing it breaks every click. */
    @Test
    void thePickersKeepTheirRedrawTargets() throws Exception {
        assertThat(toolsPicker(Set.of(), false))
                .contains("\"id\":\"" + ChatToolsPickerComponent.BODY_ID + "\"");
        assertThat(json(ChatSubAgentsComponent.node(SESSION, List.of(), false, false)))
                .contains("\"id\":\"" + ChatSubAgentsComponent.BODY_ID + "\"");
    }

    @Test
    void theSubAgentPickerCarriesTheDelegationSwitchAndOneRowPerAgent() throws Exception {
        String out = json(ChatSubAgentsComponent.node(SESSION, List.of(researcher()), true, false));

        assertThat(out).contains("\"url\":\"" + base() + "/delegation?on=false\"");
        assertThat(out).contains("\"url\":\"" + base() + "/delegate?agent=web-researcher\"");
        // The brief is typed in the composer, so Ask has to submit it.
        assertThat(out).contains("\"payload\":\"" + ChatFormComponent.formId(SESSION) + "\"");
        assertThat(out).contains("Reads the web");
    }

    /**
     * Without {@code run_agent} a roster is a list of names the model cannot
     * call, so Ask says why instead of writing a brief nothing will act on.
     */
    @Test
    void askIsDisabledUntilTheChatMayDelegate() throws Exception {
        assertThat(json(ChatSubAgentsComponent.node(SESSION, List.of(researcher()), false, false)))
                .contains("\"enabled\":false")
                .contains("Turn delegation on first");

        assertThat(json(ChatSubAgentsComponent.node(SESSION, List.of(researcher()), true, false)))
                .doesNotContain("\"enabled\":false");
    }

    /** An empty picker explains itself rather than showing an empty box. */
    @Test
    void noAgentsSaysSo() throws Exception {
        assertThat(json(ChatSubAgentsComponent.node(SESSION, List.of(), true, false)))
                .contains("No other agents are registered yet")
                .doesNotContain("/delegate?agent=");
    }

    /** A short list is explained when the agent was given a roster. */
    @Test
    void aRosterIsSaidOutLoud() throws Exception {
        assertThat(json(ChatSubAgentsComponent.node(SESSION, List.of(researcher()), true, true)))
                .contains("the roster the agent behind the chat was given");
        assertThat(json(ChatSubAgentsComponent.node(SESSION, List.of(researcher()), true, false)))
                .doesNotContain("the roster the agent behind the chat was given");
    }

    private static AgentDefinition researcher() {
        return AgentDefinition.create("web-researcher", "Reads the web and reports back",
                "You research.", null, "agent-default");
    }
}
