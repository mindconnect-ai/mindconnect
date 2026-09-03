package ai.mindconnect.agent.adapter.pg;

import ai.mindconnect.agent.tools.workspace.WorkspaceScope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PgWorkspaceStoreTest {

    private final UUID agent = UUID.randomUUID();
    private final UUID session = UUID.randomUUID();
    private PgWorkspaceStore store;

    @BeforeEach
    void setUp() {
        store = new PgWorkspaceStore(TestDb.fresh("mc_workspace_file")).initSchema();
    }

    @Test
    void aFileIsWrittenReadListedAndDeletedWithinItsScope() {
        WorkspaceScope scope = WorkspaceScope.session(agent, "david", session);
        store.write(scope, "notes.md", "# Notes\n\n");
        store.write(scope, "a.txt", "a");

        assertThat(store.exists(scope, "notes.md")).isTrue();
        assertThat(store.read(scope, "notes.md")).as("read strips, like the file store").contains("# Notes");
        assertThat(store.readBytes(scope, "notes.md")).contains("# Notes\n\n".getBytes(StandardCharsets.UTF_8));
        assertThat(store.sizeOf(scope, "notes.md")).contains(9L);
        assertThat(store.list(scope)).containsExactly("a.txt", "notes.md");

        store.write(scope, "notes.md", "replaced");
        assertThat(store.read(scope, "notes.md")).contains("replaced");
        assertThat(store.list(scope)).hasSize(2);

        store.delete(scope, "notes.md");
        assertThat(store.exists(scope, "notes.md")).isFalse();
        assertThat(store.read(scope, "notes.md")).isEmpty();
        assertThat(store.sizeOf(scope, "notes.md")).isEmpty();
        assertThat(store.list(scope)).containsExactly("a.txt");
    }

    @Test
    void theThreeScopesOfOneUserDoNotSeeEachOther() {
        store.write(WorkspaceScope.user("david"), "f.txt", "user");
        store.write(WorkspaceScope.agentUser(agent, "david"), "f.txt", "agent-user");
        store.write(WorkspaceScope.session(agent, "david", session), "f.txt", "session");
        store.write(WorkspaceScope.user("eve"), "f.txt", "eve");

        assertThat(store.read(WorkspaceScope.user("david"), "f.txt")).contains("user");
        assertThat(store.read(WorkspaceScope.agentUser(agent, "david"), "f.txt")).contains("agent-user");
        assertThat(store.read(WorkspaceScope.session(agent, "david", session), "f.txt")).contains("session");
        assertThat(store.read(WorkspaceScope.session(agent, "david", UUID.randomUUID()), "f.txt")).isEmpty();
        assertThat(store.list(WorkspaceScope.user("eve"))).containsExactly("f.txt");
    }
}
