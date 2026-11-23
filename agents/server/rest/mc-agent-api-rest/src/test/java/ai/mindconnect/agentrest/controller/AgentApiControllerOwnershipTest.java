package ai.mindconnect.agentrest.controller;

import ai.mindconnect.agent.runtime.adapter.repo.memory.InMemoryToolApprovalRepository;
import ai.mindconnect.agent.Namespace;
import ai.mindconnect.agent.ScopeSupplier;
import ai.mindconnect.agent.SessionId;
import ai.mindconnect.agent.UserId;
import ai.mindconnect.agent.runtime.adapter.repo.memory.InMemoryAgentDefinitionRepository;
import ai.mindconnect.agent.runtime.adapter.repo.memory.InMemoryAgentSessionRepository;
import ai.mindconnect.agent.runtime.adapter.repo.memory.InMemoryConversationSummaryRepository;
import ai.mindconnect.agent.runtime.adapter.repo.memory.InMemoryTodoListRepository;
import ai.mindconnect.agent.runtime.adapter.repo.memory.InMemoryWorkingMemoryRepository;
import ai.mindconnect.agent.runtime.domain.AgentDefinition;
import ai.mindconnect.agent.runtime.domain.StreamEvent;
import ai.mindconnect.agent.runtime.memory.domain.WorkingMemory;
import ai.mindconnect.agent.runtime.port.in.ChatTurnHandle;
import ai.mindconnect.agent.runtime.service.AgentChatService;
import ai.mindconnect.agent.runtime.service.AgentRegistryService;
import ai.mindconnect.agent.runtime.service.AgentSessionService;
import ai.mindconnect.agent.runtime.service.approval.ApprovalScope;
import ai.mindconnect.agent.runtime.domain.ToolApproval;
import ai.mindconnect.agent.runtime.port.out.ToolApprovalRepository;
import ai.mindconnect.agent.runtime.service.stream.UserChannels;
import ai.mindconnect.agentrest.auth.SessionAccess;
import ai.mindconnect.filestore.filesystem.FilesystemFileStore;
import ai.mindconnect.message.adapter.memory.InMemoryMessageStore;
import ai.mindconnect.message.domain.ContentPart;
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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

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
    private final InMemoryAgentSessionRepository sessions = new InMemoryAgentSessionRepository(ScopeSupplier.local());
    private final InMemoryAgentDefinitionRepository definitions = new InMemoryAgentDefinitionRepository(ScopeSupplier.local());
    private final UserChannels userChannels = new UserChannels();

    private AgentSessionService sessionService;
    /** No turn runs in these tests: what reaches the chat service is all that is looked at. */
    private final ai.mindconnect.agent.starter.runtime.bridge.RuntimeChatService chat =
            org.mockito.Mockito.mock(ai.mindconnect.agent.starter.runtime.bridge.RuntimeChatService.class);
    private final ai.mindconnect.agent.runtime.capabilities.memory.MemoryCompaction compaction =
            org.mockito.Mockito.mock(ai.mindconnect.agent.runtime.capabilities.memory.MemoryCompaction.class);
    private AgentDefinition agent;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        sessionService = new AgentSessionService(definitions, sessions,
                new InMemoryMessageStore(ScopeSupplier.local()).conversationManager(), new InMemoryWorkingMemoryRepository(ScopeSupplier.local()),
                new InMemoryConversationSummaryRepository(ScopeSupplier.local()), new InMemoryTodoListRepository(ScopeSupplier.local()),
                new InMemoryToolApprovalRepository(), userChannels);
        agent = definitions.save(AgentDefinition.create("helper", "Helps.", "You help.", null, "chat"));
        // A chat opened through the chat service is a session of the repository these tests read.
        // A chat deleted through the chat service is gone from the repository these tests read.
        org.mockito.Mockito.doAnswer(call -> {
            sessions.deleteById(call.getArgument(0));
            return null;
        }).when(chat).deleteChat(org.mockito.ArgumentMatchers.any());
        ai.mindconnect.agent.starter.runtime.bridge.ChatOpener opener =
                org.mockito.Mockito.mock(ai.mindconnect.agent.starter.runtime.bridge.ChatOpener.class);
        org.mockito.Mockito.when(opener.open(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any()))
                .thenAnswer(call -> sessionService.openChat(call.getArgument(0), call.<UserId>getArgument(1),
                        call.<String>getArgument(3), call.<List<String>>getArgument(4)));
        AgentApiController controller = new AgentApiController(new AgentRegistryService(definitions),
                sessionService, chat, compaction, opener, new FilesystemFileStore(dir, ScopeSupplier.fixed(new Namespace("test"))), userChannels,
                new SessionAccess(sessions), new ObjectMapper(), null);
        mvc = MockMvcBuilders.standaloneSetup(controller).setCustomArgumentResolvers(callers.resolver()).build();
    }

    @Test
    void aSessionStartsForTheCallerWhateverTheBodySays() throws Exception {
        callers.actAs("alice");

        mvc.perform(post("/api/sessions").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"agentId\":\"" + agent.id().value() + "\",\"userId\":\"mallory\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value("alice"));

        assertThat(sessionService.listSessions(agent.id(), UserId.of("alice"))).hasSize(1);
        assertThat(sessionService.listSessions(agent.id(), UserId.of("mallory"))).isEmpty();
    }

    @Test
    void theSessionListHoldsOnlyTheCallersSessions() throws Exception {
        String alices = sessionService.openChat(agent.id(), UserId.of("alice")).id().value();
        sessionService.openChat(agent.id(), UserId.of("bob"));
        callers.actAs("alice");

        // A userId parameter from an older client changes nothing.
        mvc.perform(get("/api/sessions").param("agentId", agent.id().value()).param("userId", "bob"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(alices));
    }

    @Test
    void someoneElsesSessionIsNotFoundOnEveryEndpoint() throws Exception {
        String bobs = sessionService.openChat(agent.id(), UserId.of("bob")).id().value();
        callers.actAs("alice");

        for (MockHttpServletRequestBuilder request : sessionEndpoints(bobs)) {
            mvc.perform(request).andExpect(status().isNotFound());
        }

        org.mockito.Mockito.verifyNoInteractions(chat, compaction);
        assertThat(sessions.findById(SessionId.of(bobs))).as("the session was not deleted").isPresent();
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
        String alices = sessionService.openChat(agent.id(), UserId.of("alice")).id().value();
        callers.actAs("alice");

        mvc.perform(get("/api/sessions/{id}/history", alices))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());
        mvc.perform(get("/api/sessions/{id}/approvals", alices)).andExpect(status().isOk());
        mvc.perform(post("/api/sessions/{id}/compress", alices))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.compressedMessages").value(0));
        mvc.perform(delete("/api/sessions/{id}", alices)).andExpect(status().isNoContent());

        org.mockito.Mockito.verify(chat).openApprovals(ai.mindconnect.agent.SessionId.of(alices));
        org.mockito.Mockito.verify(compaction).compact(ai.mindconnect.agent.runtime.api.domain.SessionId.of(alices));
        assertThat(sessions.findById(SessionId.of(alices))).isEmpty();
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
