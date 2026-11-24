package ai.mindconnect.agentrest.controller;

import ai.mindconnect.agent.Namespace;
import ai.mindconnect.agent.ScopeSupplier;
import ai.mindconnect.agent.SessionId;
import ai.mindconnect.agent.UserId;
import ai.mindconnect.agent.tools.todo.adapter.InMemoryTodoListRepository;
import ai.mindconnect.agent.chat.stream.UserChannels;
import ai.mindconnect.agentrest.auth.SessionAccess;
import ai.mindconnect.filestore.filesystem.FilesystemFileStore;
import ai.mindconnect.message.adapter.memory.InMemoryMessageStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Sessions are the caller's: opened for the caller, listed for the caller, and
 * every session-addressed endpoint answers someone else's session exactly like
 * a missing one — before anything reaches the chat service.
 */
class AgentApiControllerOwnershipTest {

    @TempDir
    Path dir;

    private final TestCallers callers = new TestCallers();
    private static final String AGENT = "a1";

    private final ai.mindconnect.agentrest.SessionFixture sessions = new ai.mindconnect.agentrest.SessionFixture();
    private final UserChannels userChannels = new UserChannels();

    /** No turn runs in these tests: what reaches the services behind the controller is all that is looked at. */
    private final ai.mindconnect.agentrest.service.SessionTurns sessionTurns =
            org.mockito.Mockito.mock(ai.mindconnect.agentrest.service.SessionTurns.class);
    private final ai.mindconnect.agentrest.service.TurnFrameStream frameStream =
            org.mockito.Mockito.mock(ai.mindconnect.agentrest.service.TurnFrameStream.class);
    private final ai.mindconnect.agent.management.api.SessionManagement management = sessions.management();
    private final ai.mindconnect.agent.runtime.capabilities.memory.MemoryCompaction compaction =
            org.mockito.Mockito.mock(ai.mindconnect.agent.runtime.capabilities.memory.MemoryCompaction.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        ai.mindconnect.agent.starter.runtime.bridge.ChatOpener opener =
                org.mockito.Mockito.mock(ai.mindconnect.agent.starter.runtime.bridge.ChatOpener.class);
        org.mockito.Mockito.when(opener.open(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any()))
                .thenAnswer(call -> sessions.openOn(call.<ai.mindconnect.agent.AgentId>getArgument(0).value(),
                        call.<UserId>getArgument(1).value()));
        AgentApiController controller = new AgentApiController(
                ai.mindconnect.agent.management.directory.WorkingDirPolicy.unrestricted(),
                new ai.mindconnect.message.adapter.memory.InMemoryMessageRepository(
                        ScopeSupplier.fixed(new Namespace("test"))),
                sessionTurns, frameStream, sessions.runtime(), compaction, opener,
                new FilesystemFileStore(dir, ScopeSupplier.fixed(new Namespace("test"))), userChannels,
                new SessionAccess(sessions.runtime()), new ObjectMapper(), null);
        mvc = MockMvcBuilders.standaloneSetup(controller).setCustomArgumentResolvers(callers.resolver()).build();
    }

    private List<ai.mindconnect.agent.management.api.ManagedSession> sessionsOf(String user) {
        return management.list(ai.mindconnect.agent.runtime.api.domain.UserId.of(user));
    }

    @Test
    void aSessionStartsForTheCallerWhateverTheBodySays() throws Exception {
        callers.actAs("alice");

        mvc.perform(post("/api/sessions").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"agentId\":\"" + AGENT + "\",\"userId\":\"mallory\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value("alice"));

        assertThat(sessionsOf("alice")).hasSize(1);
        assertThat(sessionsOf("mallory")).isEmpty();
    }

    @Test
    void theSessionListHoldsOnlyTheCallersSessions() throws Exception {
        String alices = sessions.openOn(AGENT, "alice").id().value();
        sessions.openOn(AGENT, "bob");
        callers.actAs("alice");

        // A userId parameter from an older client changes nothing.
        mvc.perform(get("/api/sessions").param("agentId", AGENT).param("userId", "bob"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(alices));
    }

    @Test
    void someoneElsesSessionIsNotFoundOnEveryEndpoint() throws Exception {
        String bobs = sessions.openOn(AGENT, "bob").id().value();
        callers.actAs("alice");

        for (MockHttpServletRequestBuilder request : sessionEndpoints(bobs)) {
            mvc.perform(request).andExpect(status().isNotFound());
        }

        org.mockito.Mockito.verifyNoInteractions(sessionTurns, frameStream, compaction);
        // Asked whose session it is, and nothing else.
        org.mockito.Mockito.verify(management, org.mockito.Mockito.atLeastOnce())
                .find(org.mockito.ArgumentMatchers.any());
        org.mockito.Mockito.verifyNoMoreInteractions(management);
        assertThat(sessionsOf("bob")).as("the session was not deleted").hasSize(1);
    }

    @Test
    void aMissingSessionAnswersTheSame() throws Exception {
        callers.actAs("alice");

        for (MockHttpServletRequestBuilder request : sessionEndpoints(SessionId.random().value())) {
            mvc.perform(request).andExpect(status().isNotFound());
        }
    }

    @Test
    void theOwnerReachesTheirSession() throws Exception {
        String alices = sessions.openOn(AGENT, "alice").id().value();
        callers.actAs("alice");

        mvc.perform(get("/api/sessions/{id}/history", alices))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());
        mvc.perform(get("/api/sessions/{id}/approvals", alices)).andExpect(status().isOk());
        mvc.perform(post("/api/sessions/{id}/compress", alices))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.compressedMessages").value(0));
        mvc.perform(delete("/api/sessions/{id}", alices)).andExpect(status().isNoContent());

        org.mockito.Mockito.verify(sessionTurns).open(ai.mindconnect.agent.runtime.api.domain.SessionId.of(alices));
        org.mockito.Mockito.verify(compaction).compact(ai.mindconnect.agent.runtime.api.domain.SessionId.of(alices));
        assertThat(sessionsOf("alice")).isEmpty();
    }

    @Test
    void theUserStreamIsTheCallersOnly() throws Exception {
        callers.actAs("alice");

        mvc.perform(get("/api/users/bob/stream")).andExpect(status().isNotFound());
        mvc.perform(get("/api/users/me/stream")).andExpect(request().asyncStarted());
        mvc.perform(get("/api/users/alice/stream")).andExpect(request().asyncStarted());
    }

    /** One request for every endpoint under {@code /api/sessions/{sessionId}}. */
    private static List<MockHttpServletRequestBuilder> sessionEndpoints(String sessionId) {
        return List.of(
                post("/api/sessions/{id}/chat", sessionId).contentType(MediaType.TEXT_PLAIN).content("Hello"),
                post("/api/sessions/{id}/chat", sessionId).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"Hello\"}"),
                delete("/api/sessions/{id}/chat", sessionId),
                get("/api/sessions/{id}/stream", sessionId),
                get("/api/sessions/{id}/history", sessionId),
                delete("/api/sessions/{id}/messages", sessionId).param("fromSeq", "1").param("toSeq", "2"),
                get("/api/sessions/{id}/memory", sessionId),
                post("/api/sessions/{id}/compress", sessionId),
                get("/api/sessions/{id}/approvals", sessionId),
                post("/api/sessions/{id}/approvals/{callId}", sessionId, "call-1").param("approved", "true"),
                post("/api/sessions/{id}/approvals/{callId}/continue", sessionId, "call-1").param("approved", "true"),
                delete("/api/sessions/{id}", sessionId));
    }
}
