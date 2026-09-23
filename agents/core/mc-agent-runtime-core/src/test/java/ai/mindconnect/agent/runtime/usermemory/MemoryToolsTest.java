package ai.mindconnect.agent.runtime.usermemory;

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
    void anUnknownTypeNamesTheOnesThereAre() {
        MemoryWriteTool write = new MemoryWriteTool(service, ALICE, null);

        assertThatThrownBy(() -> write.execute(Map.of("name", "x", "type", "fact",
                "description", "d", "content", "c")))
                .hasMessageContaining("user|feedback|project|reference");
    }
}
