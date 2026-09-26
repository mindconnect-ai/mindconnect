package ai.mindconnect.adminui.ui.controller;

import ai.mindconnect.adminui.ui.component.UserMemoryComponent;
import ai.mindconnect.adminui.ui.component.UserMemoryComponent.Row;
import ai.mindconnect.agent.UserId;
import ai.mindconnect.agent.runtime.adapter.repo.memory.InMemoryUserMemoryRepository;
import ai.mindconnect.agent.runtime.usermemory.MemoryEntry;
import ai.mindconnect.agent.runtime.usermemory.MemoryType;
import ai.mindconnect.agent.runtime.usermemory.UserMemoryService;
import org.junit.jupiter.api.Test;

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

        String page = json(controller.list(null));

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
        assertThat(json(controller.delete(Row.of(bobs).id()))).contains("Memory deleted");
        assertThat(memory.list(UserId.of("bob"))).isEmpty();
    }

    @Test
    void withoutTheMemoryThePageSaysSo() throws Exception {
        assertThat(json(new MemoryAdminUiController((UserMemoryService) null, null).list(null))).contains("keeps no memory");
    }
}
