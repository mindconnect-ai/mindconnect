package ai.mindconnect.agent.runtime.usermemory;

import ai.mindconnect.agent.AgentId;
import ai.mindconnect.agent.SessionId;
import ai.mindconnect.agent.UserId;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MemoryToolsTest {

    private static final UserId ALICE = UserId.of("alice");

    private final UserMemoryService service = new UserMemoryService(new MapUserMemoryRepository());

    @Test
    void writeReadAndDeleteAMemoryOfTheChatsUser() {
        MemoryWriteTool write = new MemoryWriteTool(service, ALICE, SessionId.of("s1"));
        MemoryReadTool read = new MemoryReadTool(service, ALICE);
        MemoryDeleteTool delete = new MemoryDeleteTool(service, ALICE);

        assertThat(write.execute(Map.of("name", "role", "type", "user",
                "description", "Head of purchasing", "content", "Leads the purchasing team in Erlangen.")))
                .isEqualTo("Saved memory 'role'.");
        assertThat(write.execute(Map.of("name", "role", "type", "USER",
                "description", "Head of purchasing", "content", "Leads purchasing in Nuremberg now.")))
                .isEqualTo("Updated memory 'role'.");

        assertThat(read.execute(Map.of("name", "role")))
                .startsWith("# role (user)\nHead of purchasing")
                .contains("Leads purchasing in Nuremberg now.");
        assertThat(read.execute(Map.of())).isEqualTo("1 memories:\n- role (user): Head of purchasing");

        assertThat(delete.execute(Map.of("name", "role"))).isEqualTo("Deleted memory 'role'.");
        assertThat(delete.execute(Map.of("name", "role"))).isEqualTo("No memory named 'role'.");
        assertThat(read.execute(Map.of())).isEqualTo("No memories saved yet.");
    }

    @Test
    void anotherUsersToolsSeeNothingOfIt() {
        new MemoryWriteTool(service, ALICE, null).execute(Map.of("name", "role", "type", "user",
                "description", "d", "content", "c"));

        assertThat(new MemoryReadTool(service, UserId.of("mallory")).execute(Map.of("name", "role")))
                .startsWith("No memory named 'role'");
    }

    @Test
    void anAgentScopedBindingWritesAndReadsTheAgentsOwnMemory() {
        AgentId secretary = AgentId.of("secretary");
        new MemoryWriteTool(service, ALICE, secretary, null, MemoryReach.AGENT).execute(Map.of("name", "travel",
                "type", "project", "description", "Books via Egencia"));

        assertThat(service.read(ALICE, "travel")).as("not in the user's own memory").isEmpty();
        assertThat(service.read(ALICE, secretary, "travel")).isPresent();
        assertThat(new MemoryReadTool(service, ALICE, AgentId.of("coder"), MemoryReach.AGENT)
                .execute(Map.of("name", "travel"))).as("another agent sees nothing of it").startsWith("No memory");
        assertThat(new MemoryReadTool(service, ALICE, null, MemoryReach.USER).execute(Map.of()))
                .isEqualTo("No memories saved yet.");
    }

    @Test
    void underBothTheModelChoosesWhereAWriteGoesAndAReadLooksInTheAgentsFirst() {
        AgentId secretary = AgentId.of("secretary");
        MemoryWriteTool write = new MemoryWriteTool(service, ALICE, secretary, null, MemoryReach.BOTH);

        assertThat(write.parametersSchema().get("required").toString()).contains("scope");
        assertThatThrownBy(() -> write.execute(Map.of("name", "x", "type", "user", "description", "d")))
                .hasMessageContaining("'scope' is required");
        assertThat(write.execute(Map.of("name", "role", "type", "user", "description", "Buyer", "scope", "user")))
                .isEqualTo("Saved memory 'role' in the user's memory.");
        assertThat(write.execute(Map.of("name", "role", "type", "project", "description", "Travel desk", "scope", "agent")))
                .isEqualTo("Saved memory 'role' in your own memory.");

        MemoryReadTool read = new MemoryReadTool(service, ALICE, secretary, MemoryReach.BOTH);
        assertThat(read.execute(Map.of("name", "role"))).contains("Travel desk");
        assertThat(read.execute(Map.of("name", "role", "scope", "user"))).contains("Buyer");
        assertThat(new MemoryDeleteTool(service, ALICE, secretary, MemoryReach.BOTH).execute(Map.of("name", "role")))
                .isEqualTo("Deleted memory 'role' from your own memory.");
        assertThat(read.execute(Map.of("name", "role"))).as("the user's own one is left").contains("Buyer");
    }

    @Test
    void anUnknownTypeNamesTheOnesThereAre() {
        MemoryWriteTool write = new MemoryWriteTool(service, ALICE, null);

        assertThatThrownBy(() -> write.execute(Map.of("name", "x", "type", "fact",
                "description", "d", "content", "c")))
                .hasMessageContaining("user|feedback|project|reference");
    }
}
