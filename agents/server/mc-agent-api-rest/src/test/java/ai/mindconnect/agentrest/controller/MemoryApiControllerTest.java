package ai.mindconnect.agentrest.controller;

import ai.mindconnect.agent.AgentId;
import ai.mindconnect.agent.UserId;
import ai.mindconnect.agent.runtime.adapter.repo.memory.InMemoryUserMemoryRepository;
import ai.mindconnect.agent.runtime.usermemory.MemoryEntry;
import ai.mindconnect.agent.runtime.usermemory.MemoryType;
import ai.mindconnect.agent.runtime.usermemory.UserMemoryService;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The REST API reads and deletes the caller's own memory — never anyone else's. */
class MemoryApiControllerTest {

    private static final UserId ALICE = UserId.of("alice");
    private static final UserId BOB = UserId.of("bob");
    private static final AgentId SECRETARY = AgentId.of("secretary");

    private final UserMemoryService memory = new UserMemoryService(new InMemoryUserMemoryRepository());
    private final MemoryApiController controller = new MemoryApiController(memory);

    @Test
    void theCallerListsTheirOwnEntriesAndNarrowsToOneMemory() {
        memory.write(ALICE, "role", MemoryType.USER, "Buyer", "c", null);
        memory.write(ALICE, SECRETARY, "travel", MemoryType.PROJECT, "Egencia", "c", null);
        memory.write(BOB, "role", MemoryType.USER, "Engineer", "c", null);

        assertThat(controller.list(ALICE, null)).extracting(MemoryEntry::description)
                .containsExactlyInAnyOrder("Buyer", "Egencia");
        assertThat(controller.list(ALICE, "shared")).extracting(MemoryEntry::description).containsExactly("Buyer");
        assertThat(controller.list(ALICE, "secretary")).extracting(MemoryEntry::description).containsExactly("Egencia");
    }

    @Test
    void getAndDeleteReachOnlyTheCallersOwnEntry() {
        memory.write(BOB, "role", MemoryType.USER, "Engineer", "c", null);
        memory.write(ALICE, SECRETARY, "travel", MemoryType.PROJECT, "Egencia", "c", null);

        assertThat(controller.get(ALICE, "role", null).getStatusCode().value()).isEqualTo(404);
        assertThat(controller.delete(ALICE, "role", null).getStatusCode().value()).isEqualTo(404);
        assertThat(memory.read(BOB, "role")).isPresent();

        assertThat(controller.get(ALICE, "travel", null).getStatusCode().value())
                .as("an agent's entry is found only with its agent").isEqualTo(404);
        assertThat(controller.get(ALICE, "travel", "secretary").getBody().description()).isEqualTo("Egencia");
        assertThat(controller.delete(ALICE, "travel", "secretary").getStatusCode().value()).isEqualTo(204);
        assertThat(memory.list(ALICE)).isEmpty();
    }

    @Test
    void aNameOfNothingIsABadRequestAndNoMemoryIsNotFound() {
        assertThatThrownBy(() -> controller.get(ALICE, "...", null)).isInstanceOf(IllegalArgumentException.class);
        assertThat(controller.badRequest(new IllegalArgumentException("x")).getStatusCode().value()).isEqualTo(400);
        assertThatThrownBy(() -> new MemoryApiController((UserMemoryService) null).list(ALICE, null))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("404");
    }
}
