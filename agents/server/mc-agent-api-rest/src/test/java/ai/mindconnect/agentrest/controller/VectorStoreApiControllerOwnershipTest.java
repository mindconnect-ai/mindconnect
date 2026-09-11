package ai.mindconnect.agentrest.controller;

import ai.mindconnect.agent.UserId;
import ai.mindconnect.agent.runtime.adapter.repo.memory.InMemoryAgentDefinitionRepository;
import ai.mindconnect.agent.runtime.adapter.repo.memory.InMemoryAgentSessionRepository;
import ai.mindconnect.agent.runtime.adapter.repo.memory.InMemoryConversationSummaryRepository;
import ai.mindconnect.agent.runtime.adapter.repo.memory.InMemoryTodoListRepository;
import ai.mindconnect.agent.runtime.adapter.repo.memory.InMemoryWorkingMemoryRepository;
import ai.mindconnect.agent.runtime.domain.AgentDefinition;
import ai.mindconnect.agent.runtime.service.AgentSessionService;
import ai.mindconnect.agent.runtime.service.approval.ToolApprovalStore;
import ai.mindconnect.agent.runtime.service.stream.UserChannels;
import ai.mindconnect.agentrest.auth.SessionAccess;
import ai.mindconnect.agentrest.auth.VectorStoreAccess;
import ai.mindconnect.agentrest.service.VectorStoreService;
import ai.mindconnect.message.adapter.memory.InMemoryMessageStore;
import ai.mindconnect.vectorstore.tools.VectorStoreInstance;
import ai.mindconnect.vectorstore.tools.VectorStoreTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A knowledge base is shared; a chat's upload store answers only to the
 * chat's user. Nobody else sees it listed, and every store-addressed endpoint
 * answers 404 before the service is asked — for a registered store and for
 * one that only carries the name. Ingestion hands the caller on, so the
 * service reads only a file the caller may read.
 */
class VectorStoreApiControllerOwnershipTest {

    private static final VectorStoreTemplate TEMPLATE =
            new VectorStoreTemplate("default", "memory", Map.of(), "embeddings", null, Map.of());

    private final TestCallers callers = new TestCallers();
    private final InMemoryAgentSessionRepository sessions = new InMemoryAgentSessionRepository();
    private final InMemoryAgentDefinitionRepository definitions = new InMemoryAgentDefinitionRepository();
    private final VectorStoreService service = mock(VectorStoreService.class);

    private AgentSessionService sessionService;
    private AgentDefinition agent;
    private String alicesStore;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        sessionService = new AgentSessionService(definitions, sessions,
                new InMemoryMessageStore().conversationManager(), new InMemoryWorkingMemoryRepository(),
                new InMemoryConversationSummaryRepository(), new InMemoryTodoListRepository(),
                new ToolApprovalStore(), new UserChannels());
        agent = definitions.save(AgentDefinition.create("helper", "Helps.", "You help.", null, "chat"));
        String alicesSession = sessionService.openChat(agent.id(), UserId.of("alice")).id().value();
        alicesStore = VectorStoreAccess.CHAT_STORE_PREFIX + alicesSession;

        VectorStoreInstance chatStore = VectorStoreInstance.fromTemplate(
                alicesStore, TEMPLATE, VectorStoreInstance.Scope.SESSION, alicesSession);
        VectorStoreInstance knowledgeBase = VectorStoreInstance.fromTemplate(
                "handbook", TEMPLATE, VectorStoreInstance.Scope.GLOBAL, null);
        when(service.instances()).thenReturn(List.of(chatStore, knowledgeBase));
        when(service.instance(alicesStore)).thenReturn(Optional.of(chatStore));
        when(service.instance("handbook")).thenReturn(Optional.of(knowledgeBase));

        VectorStoreApiController controller =
                new VectorStoreApiController(service, new VectorStoreAccess(new SessionAccess(sessions)));
        mvc = MockMvcBuilders.standaloneSetup(controller).setCustomArgumentResolvers(callers.resolver()).build();
    }

    @Test
    void theListHoldsTheKnowledgeBasesAndOnlyTheCallersOwnChatStores() throws Exception {
        callers.actAs("alice");
        mvc.perform(get("/api/vector-stores/stores"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));

        callers.actAs("bob");
        mvc.perform(get("/api/vector-stores/stores"))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].name").value("handbook"));
    }

    @Test
    void someoneElsesChatStoreIsNotFoundAndTheServiceIsNeverAsked() throws Exception {
        callers.actAs("bob");

        mvc.perform(get("/api/vector-stores/stores/{name}", alicesStore)).andExpect(status().isNotFound());
        mvc.perform(json(post("/api/vector-stores/stores/{name}/search", alicesStore), "{\"query\":\"salary\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(json(post("/api/vector-stores/stores/{name}/chunks", alicesStore), "{\"text\":\"planted\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(json(post("/api/vector-stores/stores/{name}/ingest", alicesStore), "{\"fileId\":\"file-1\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(delete("/api/vector-stores/stores/{name}", alicesStore)).andExpect(status().isNotFound());

        verify(service, never()).search(any(), any(), anyInt(), anyDouble());
        verify(service, never()).upsertChunk(any(), any(), any(), any(), any(), any());
        verify(service, never()).ingestStoredFile(any(), any(), any());
        verify(service, never()).deleteStore(any());
    }

    @Test
    void theChatsUserReachesTheirStoreAndEveryoneReachesAKnowledgeBase() throws Exception {
        when(service.search(any(), any(), anyInt(), anyDouble())).thenReturn(List.of());

        callers.actAs("alice");
        mvc.perform(get("/api/vector-stores/stores/{name}", alicesStore)).andExpect(status().isOk());
        mvc.perform(json(post("/api/vector-stores/stores/{name}/search", alicesStore), "{\"query\":\"notes\"}"))
                .andExpect(status().isOk());

        callers.actAs("bob");
        mvc.perform(json(post("/api/vector-stores/stores/{name}/search", "handbook"), "{\"query\":\"notes\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void aChatStoreNameIsTheChatsUsersEvenBeforeTheStoreIsRegistered() throws Exception {
        when(service.search(any(), any(), anyInt(), anyDouble())).thenReturn(List.of());
        String unregistered = VectorStoreAccess.CHAT_STORE_PREFIX
                + sessionService.openChat(agent.id(), UserId.of("alice")).id().value();

        callers.actAs("bob");
        mvc.perform(json(post("/api/vector-stores/stores/{name}/chunks", unregistered), "{\"text\":\"planted\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(json(post("/api/vector-stores/stores/{name}/search", "session-no-such-chat"), "{\"query\":\"x\"}"))
                .andExpect(status().isNotFound());

        callers.actAs("alice");
        mvc.perform(json(post("/api/vector-stores/stores/{name}/search", unregistered), "{\"query\":\"x\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void noStoreIsCreatedUnderAChatStoreName() throws Exception {
        callers.actAs("alice");

        mvc.perform(json(post("/api/vector-stores/stores"), "{\"name\":\"session-anything\"}"))
                .andExpect(status().isBadRequest());

        verify(service, never()).createStore(any(), any());
    }

    @Test
    void ingestionReadsTheFileAsTheCaller() throws Exception {
        when(service.ingestStoredFile(any(), any(), any())).thenReturn("ingested");

        callers.actAs("bob");
        mvc.perform(json(post("/api/vector-stores/stores/{name}/ingest", "handbook"), "{\"fileId\":\"file-1\"}"))
                .andExpect(status().isOk());

        verify(service).ingestStoredFile("handbook", "file-1", UserId.of("bob"));
    }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder json(
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request, String body) {
        return request.contentType(MediaType.APPLICATION_JSON).content(body);
    }
}
