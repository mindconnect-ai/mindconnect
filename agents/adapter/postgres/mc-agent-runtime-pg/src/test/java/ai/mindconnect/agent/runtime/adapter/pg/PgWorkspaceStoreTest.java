package ai.mindconnect.agent.runtime.adapter.pg;

import ai.mindconnect.agent.AgentId;
import ai.mindconnect.agent.Namespace;
import ai.mindconnect.agent.SessionId;
import ai.mindconnect.agent.UserId;
import ai.mindconnect.agent.runtime.tools.workspace.WorkspaceScope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class PgWorkspaceStoreTest {

    private static final Namespace NS = new Namespace("test");
    private static final UserId DAVID = UserId.of("david");
    private static final UserId EVE = UserId.of("eve");

    private final AgentId agent = AgentId.random();
    private final SessionId session = SessionId.random();
    private PgWorkspaceStore store;

    @BeforeEach
    void setUp() {
        store = new PgWorkspaceStore(TestDb.fresh("mc_workspace_file"), NS).initSchema();
    }

    @Test
    void aFileIsWrittenReadListedAndDeletedWithinItsScope() {
        WorkspaceScope scope = WorkspaceScope.session(agent, DAVID, session);
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
        store.write(WorkspaceScope.user(DAVID), "f.txt", "user");
        store.write(WorkspaceScope.agentUser(agent, DAVID), "f.txt", "agent-user");
        store.write(WorkspaceScope.session(agent, DAVID, session), "f.txt", "session");
        store.write(WorkspaceScope.user(EVE), "f.txt", "eve");

        assertThat(store.read(WorkspaceScope.user(DAVID), "f.txt")).contains("user");
        assertThat(store.read(WorkspaceScope.agentUser(agent, DAVID), "f.txt")).contains("agent-user");
        assertThat(store.read(WorkspaceScope.session(agent, DAVID, session), "f.txt")).contains("session");
        assertThat(store.read(WorkspaceScope.session(agent, DAVID, SessionId.random()), "f.txt")).isEmpty();
        assertThat(store.list(WorkspaceScope.user(EVE))).containsExactly("f.txt");
    }
}
