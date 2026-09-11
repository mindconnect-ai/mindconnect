package ai.mindconnect.agentrest.controller;

import ai.mindconnect.agent.AgentId;
import ai.mindconnect.agent.UserId;
import ai.mindconnect.agent.runtime.adapter.repo.memory.InMemoryAgentSessionRepository;
import ai.mindconnect.agent.runtime.adapter.repo.memory.InMemoryWorkspaceStore;
import ai.mindconnect.agent.runtime.domain.AgentSession;
import ai.mindconnect.agent.runtime.tools.workspace.WorkspaceScope;
import ai.mindconnect.agentrest.auth.SessionAccess;
import ai.mindconnect.message.domain.ConversationId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Every workspace is the caller's: a path names the caller's own id or
 * {@code me}, a session workspace opens for the session's owner, and anything
 * else answers 404.
 */
class WorkspaceApiControllerOwnershipTest {

    private static final AgentId AGENT = AgentId.of("default-chat");

    private final TestCallers callers = new TestCallers();
    private final InMemoryAgentSessionRepository sessions = new InMemoryAgentSessionRepository();
    private final InMemoryWorkspaceStore store = new InMemoryWorkspaceStore();
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        var controller = new WorkspaceApiController(store, new SessionAccess(sessions));
        mvc = MockMvcBuilders.standaloneSetup(controller).setCustomArgumentResolvers(callers.resolver()).build();
        store.write(WorkspaceScope.user(UserId.of("alice")), "notes.md", "alice's notes");
        store.write(WorkspaceScope.user(UserId.of("bob")), "notes.md", "bob's notes");
        store.write(WorkspaceScope.agentUser(AGENT, UserId.of("alice")), "memory.md", "about alice");
        store.write(WorkspaceScope.agentUser(AGENT, UserId.of("bob")), "memory.md", "about bob");
    }

    @Test
    void theUserWorkspaceIsTheCallersOwn() throws Exception {
        callers.actAs("alice");

        mvc.perform(get("/api/workspaces/user/me/files"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("notes.md"));
        mvc.perform(get("/api/workspaces/user/alice/files/notes.md"))
                .andExpect(status().isOk())
                .andExpect(content().string("alice's notes"));
        mvc.perform(get("/api/workspaces/user/bob/files")).andExpect(status().isNotFound());
        mvc.perform(get("/api/workspaces/user/bob/files/notes.md")).andExpect(status().isNotFound());
    }

    @Test
    void theAgentWorkspaceIsTheCallersOwn() throws Exception {
        callers.actAs("alice");

        mvc.perform(get("/api/workspaces/agent/{agent}/user/me/files/memory.md", AGENT.value()))
                .andExpect(status().isOk())
                .andExpect(content().string("about alice"));
        mvc.perform(get("/api/workspaces/agent/{agent}/user/bob/files", AGENT.value()))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/workspaces/agent/{agent}/user/bob/files/memory.md", AGENT.value()))
                .andExpect(status().isNotFound());
    }

    @Test
    void aSessionWorkspaceOpensForTheSessionsOwner() throws Exception {
        AgentSession alices = session("alice");
        AgentSession bobs = session("bob");
        store.write(WorkspaceScope.session(AGENT, UserId.of("bob"), bobs.id()), "draft.md", "bob's draft");
        callers.actAs("alice");

        mvc.perform(get("/api/workspaces/session/{id}/files", alices.id().value()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());
        mvc.perform(get("/api/workspaces/session/{id}/files", bobs.id().value()))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/workspaces/session/{id}/files/draft.md", bobs.id().value()))
                .andExpect(status().isNotFound());
    }

    private AgentSession session(String owner) {
        return sessions.create(AgentSession.start(AGENT, UserId.of(owner), ConversationId.random()));
    }
}
