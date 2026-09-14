package ai.mindconnect.chatui.ui.component;

import ai.mindconnect.agent.SessionId;
import ai.mindconnect.agent.runtime.domain.AgentDefinition;
import ai.mindconnect.chatui.ui.component.ChatToolsPickerComponent.ToolState;
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
 * pinned here is the pair of things that makes that work — every switch
 * points at the states it is not in, and the redraw target id stays what
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

    private static String toolsPicker(Map<String, ToolState> states) throws Exception {
        return json(ChatToolsPickerComponent.node(SESSION,
                Map.of("files", List.of("read_file", "bash"), "web", List.of("web_search")),
                states, Map.of("web_search", "brave")));
    }

    /** Every choice but the current one is a click away; the current one has nothing to do. */
    @Test
    void aToolOffersTheStatesItIsNotIn() throws Exception {
        String out = toolsPicker(Map.of("read_file", ToolState.ON, "web_search", ToolState.SEARCH));

        assertThat(out)
                .contains("\"url\":\"" + base() + "/tools?tool=read_file&state=OFF\"")
                .contains("\"url\":\"" + base() + "/tools?tool=read_file&state=SEARCH\"")
                .doesNotContain("/tools?tool=read_file&state=ON")
                .contains("\"url\":\"" + base() + "/tools?tool=web_search&state=ON\"")
                .doesNotContain("/tools?tool=web_search&state=SEARCH")
                .contains("\"url\":\"" + base() + "/tools?tool=bash&state=SEARCH\"")
                .doesNotContain("/tools?tool=bash&state=OFF");
    }

    /** Tool search is not a switch of its own — it is there when something is set to Search. */
    @Test
    void toolSearchIsSaidOnlyWhenSomethingIsSearchable() throws Exception {
        assertThat(toolsPicker(Map.of("read_file", ToolState.ON)))
                .doesNotContain("tool-search").doesNotContain("Tool search is on")
                .contains("Tools · 1 on, 0 by search, 3 available");
        assertThat(toolsPicker(Map.of("web_search", ToolState.SEARCH)))
                .contains("Tool search is on")
                .contains("Tools · 0 on, 1 by search, 3 available");
    }

    /**
     * A group sets all its tools at once, and shows a state only when they all
     * share it — a mixed group has every choice a click away.
     */
    @Test
    void aGroupSwitchSetsAllItsTools() throws Exception {
        String mixed = toolsPicker(Map.of("read_file", ToolState.ON));
        assertThat(mixed)
                .contains("\"url\":\"" + base() + "/tools/group?group=files&state=OFF\"")
                .contains("\"url\":\"" + base() + "/tools/group?group=files&state=ON\"")
                .contains("\"url\":\"" + base() + "/tools/group?group=files&state=SEARCH\"");

        String uniform = toolsPicker(Map.of("read_file", ToolState.ON, "bash", ToolState.ON));
        assertThat(uniform)
                .doesNotContain("/tools/group?group=files&state=ON")
                .contains("/tools/group?group=files&state=OFF");
    }

    /**
     * A group opens when something in it is on or searchable, so a chat's
     * actual reach is readable without a click; the rest stay shut, because a
     * picker of sixty rows is not a picker.
     */
    @Test
    void onlyTheGroupsWithSomethingOnStartOpen() throws Exception {
        String out = toolsPicker(Map.of("web_search", ToolState.SEARCH));

        assertThat(out)
                .contains("\"collapseSummary\":\"Web  ·  1 of 1\"")
                .contains("\"collapseSummary\":\"Files  ·  0 of 2\"")
                .contains("\"collapseOpen\":true")
                .contains("\"collapseOpen\":false");
    }

    /** Where a tool comes from is worth a line when the registry knows it. */
    @Test
    void aToolNamesItsSource() throws Exception {
        assertThat(toolsPicker(Map.of())).contains("\"description\":\"from brave\"");
    }

    /** The body id is what a toggle REPLACEs — losing it breaks every click. */
    @Test
    void thePickersKeepTheirRedrawTargets() throws Exception {
        assertThat(toolsPicker(Map.of()))
                .contains("\"id\":\"" + ChatToolsPickerComponent.BODY_ID + "\"");
        assertThat(json(ChatSubAgentsComponent.node(SESSION, List.of(), Set.of())))
                .contains("\"id\":\"" + ChatSubAgentsComponent.BODY_ID + "\"");
    }

    private static String subAgents(Set<String> selected) throws Exception {
        return json(ChatSubAgentsComponent.node(SESSION, List.of(researcher(), verifier()), selected));
    }

    /** Every agent is offered with its own switch; on are the roster's. */
    @Test
    void everyAgentHasItsOwnSwitch() throws Exception {
        String out = subAgents(Set.of("web-researcher"));

        assertThat(out)
                .contains("\"url\":\"" + base() + "/subagents?agent=web-researcher&on=false\"")
                .contains("\"url\":\"" + base() + "/subagents?agent=verifier&on=true\"")
                .doesNotContain("agent=web-researcher&on=true")
                .contains("Sub-agents · 1 of 2 on")
                .doesNotContain("/delegation");
        assertThat(subAgents(Set.of()))
                .contains("Sub-agents · 0 of 2 on")
                .contains("calls no other agent");
    }

    /** A roster names agents as typed; a differently cased name is still the agent. */
    @Test
    void theRosterIsReadIgnoringCase() throws Exception {
        assertThat(subAgents(Set.of("Web-Researcher"))).contains("Sub-agents · 1 of 2 on");
    }

    /** Test opens a dialog of its own, whether the agent is on or not. */
    @Test
    void everyAgentCanBeTested() throws Exception {
        assertThat(subAgents(Set.of()))
                .contains("\"url\":\"" + base() + "/subagents/test-dialog?agent=web-researcher\"")
                .contains("\"url\":\"" + base() + "/subagents/test-dialog?agent=verifier\"");
    }

    /** Send submits the dialog's own form, and the answer lands under it. */
    @Test
    void theTestDialogSendsItsFormAndShowsTheAnswer() throws Exception {
        String empty = json(ChatSubAgentTestComponent.node(SESSION, researcher(), null, null));
        assertThat(empty)
                .contains("\"url\":\"" + base() + "/subagents/test?agent=web-researcher\"")
                .contains("\"payload\":\"" + ChatSubAgentTestComponent.formId(SESSION) + "\"")
                .contains("\"id\":\"" + ChatSubAgentTestComponent.BODY_ID + "\"")
                .doesNotContain("Answered");

        assertThat(json(ChatSubAgentTestComponent.node(SESSION, researcher(), "hi",
                ChatSubAgentTestComponent.Result.answered("**Hello**", 12))))
                .contains("✓ Answered · 12 ms").contains("**Hello**");
        assertThat(json(ChatSubAgentTestComponent.node(SESSION, researcher(), "hi",
                ChatSubAgentTestComponent.Result.failed("boom", 3))))
                .contains("✗ Failed · 3 ms").contains("boom");
    }

    /** An empty picker explains itself rather than showing an empty box. */
    @Test
    void noAgentsSaysSo() throws Exception {
        assertThat(json(ChatSubAgentsComponent.node(SESSION, List.of(), Set.of())))
                .contains("No other agents are registered yet")
                .doesNotContain("/subagents/test-dialog");
    }

    private static AgentDefinition verifier() {
        return AgentDefinition.create("verifier", "Checks claims",
                "You verify.", null, "agent-default");
    }

    private static AgentDefinition researcher() {
        return AgentDefinition.create("web-researcher", "Reads the web and reports back",
                "You research.", null, "agent-default");
    }
}
