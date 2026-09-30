package ai.mindconnect.adminui.ui.controller;

import ai.mindconnect.adminui.ui.component.UserMemoryComponent;
import ai.mindconnect.adminui.ui.component.UserMemoryComponent.Row;
import ai.mindconnect.agent.AgentId;
import ai.mindconnect.agent.UserId;
import ai.mindconnect.agent.runtime.domain.AgentDefinition;
import ai.mindconnect.agent.tool.AgentTool;
import ai.mindconnect.agent.runtime.adapter.repo.memory.InMemoryUserMemoryRepository;
import ai.mindconnect.agent.runtime.usermemory.MemoryEntry;
import ai.mindconnect.agent.runtime.usermemory.MemoryType;
import ai.mindconnect.agent.runtime.usermemory.UserMemoryService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static ai.mindconnect.adminui.ui.controller.UserMemoryUiControllerTest.json;
import static org.assertj.core.api.Assertions.assertThat;

/** The admin sees every user's entries in the namespace, filters them by user, and deletes any. */
class MemoryAdminUiControllerTest {

    private final UserMemoryService memory = new UserMemoryService(new InMemoryUserMemoryRepository());
    private final MemoryAdminUiController controller = new MemoryAdminUiController(memory, null);

    @Test
    void thePageListsEveryUsersEntriesWithTheUser() throws Exception {
        memory.write(UserId.of("alice"), "role", MemoryType.USER, "Head of purchasing", "c", null);
        memory.write(UserId.of("bob"), "stack", MemoryType.PROJECT, "Java 21", "c", null);

        String page = json(controller.list(null, null));

        assertThat(page).contains("/admin/memories", UserMemoryComponent.ADMIN_TABLE_ID, "Memory (2)",
                "Head of purchasing", "Java 21", "\"alice\"", "\"bob\"");
    }

    @Test
    void theFilterKeepsTheUsersWhoseIdContainsTheText() throws Exception {
        memory.write(UserId.of("alice"), "role", MemoryType.USER, "Head of purchasing", "c", null);
        memory.write(UserId.of("bob"), "stack", MemoryType.PROJECT, "Java 21", "c", null);

        assertThat(json(controller.search(Map.of("q", "ALI")))).contains("Head of purchasing")
                .doesNotContain("Java 21");
    }

    @Test
    void anAdminDeletesAnyUsersEntry() throws Exception {
        MemoryEntry bobs = memory.write(UserId.of("bob"), "stack", MemoryType.PROJECT, "Java 21", "c", null).entry();

        assertThat(json(controller.view(Row.of(bobs).id()))).contains("Java 21");
        assertThat(json(controller.delete(Row.of(bobs).id(), null))).contains("Memory deleted");
        assertThat(memory.list(UserId.of("bob"))).isEmpty();
    }

    @Test
    void theMemoryFilterNarrowsToTheSharedMemoryOrOneAgents() throws Exception {
        memory.write(UserId.of("alice"), "role", MemoryType.USER, "Head of purchasing", "c", null);
        memory.write(UserId.of("alice"), AgentId.of("sec"), "travel", MemoryType.PROJECT, "Egencia", "c", null);

        assertThat(json(controller.search(Map.of("agent", "shared")))).contains("Head of purchasing")
                .doesNotContain("Egencia");
        assertThat(json(controller.search(Map.of("agent", "sec")))).contains("Egencia")
                .doesNotContain("Head of purchasing");
        assertThat(json(controller.list(null, null))).as("the filter offers the agent that keeps entries")
                .contains("Agent: sec", "All memories");
    }

    @Test
    void anAgentWithItsOwnMemoryGetsATabWithWhatItKeepsAboutItsUsers() throws Exception {
        AgentDefinition secretary = agent("secretary", "agent");
        memory.write(UserId.of("alice"), secretary.id(), "travel", MemoryType.PROJECT, "Egencia", "c", null);
        memory.write(UserId.of("bob"), secretary.id(), "hotel", MemoryType.PROJECT, "Marriott", "c", null);
        memory.write(UserId.of("alice"), "role", MemoryType.USER, "Head of purchasing", "c", null);

        String tab = json(controller.agentTab(secretary).orElseThrow());

        assertThat(tab).contains(UserMemoryComponent.AGENT_TABLE_ID, "Egencia", "Marriott", "\"alice\"", "\"bob\"",
                "?agent=" + secretary.id().value()).doesNotContain("Head of purchasing");
    }

    @Test
    void anAgentOnTheSharedMemoryHasNoTabUnlessEntriesOfItsOwnAreLeft() throws Exception {
        AgentDefinition chat = agent("default-chat", null);
        assertThat(controller.agentTab(chat)).isEmpty();
        assertThat(controller.agentTab(agent("plain", "user"))).isEmpty();

        memory.write(UserId.of("alice"), chat.id(), "old", MemoryType.USER, "Left from before", "c", null);
        assertThat(json(controller.agentTab(chat).orElseThrow())).contains("left from before", "Left from before");
    }

    @Test
    void aDeleteFromTheAgentsTabRedrawsThatTable() throws Exception {
        AgentDefinition secretary = agent("secretary", "agent");
        MemoryEntry travel = memory.write(UserId.of("alice"), secretary.id(), "travel", MemoryType.PROJECT,
                "Egencia", "c", null).entry();

        String patch = json(controller.delete(Row.of(travel).id(), secretary.id().value()));

        assertThat(patch).contains(UserMemoryComponent.AGENT_TABLE_ID, "Memory deleted")
                .doesNotContain(UserMemoryComponent.ADMIN_TABLE_ID);
    }

    private static AgentDefinition agent(String name, String scope) {
        List<AgentTool> tools = scope == null
                ? List.of(AgentTool.of("memory_write"))
                : List.of(AgentTool.of("memory_write", null, Map.of("scope", scope)));
        return AgentDefinition.create(name, "d", "p", null, "cfg").withTools(tools);
    }

    @Test
    void withoutTheMemoryThePageSaysSo() throws Exception {
        assertThat(json(new MemoryAdminUiController((UserMemoryService) null, null).list(null, null))).contains("keeps no memory");
    }
}
