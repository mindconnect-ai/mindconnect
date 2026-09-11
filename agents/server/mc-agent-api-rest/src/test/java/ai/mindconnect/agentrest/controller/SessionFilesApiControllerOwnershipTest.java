package ai.mindconnect.agentrest.controller;

import ai.mindconnect.agent.AgentId;
import ai.mindconnect.agent.Namespace;
import ai.mindconnect.agent.UserId;
import ai.mindconnect.agent.runtime.adapter.repo.memory.InMemoryAgentSessionRepository;
import ai.mindconnect.agent.runtime.domain.AgentSession;
import ai.mindconnect.agentrest.auth.SessionAccess;
import ai.mindconnect.filestore.FileStore;
import ai.mindconnect.filestore.StoredFile;
import ai.mindconnect.filestore.filesystem.FilesystemFileStore;
import ai.mindconnect.message.domain.ConversationId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Session files open only for the session's owner, and a file attached by id
 * must be one the caller may read. The checks come before the attach service,
 * which is why none is wired here.
 */
class SessionFilesApiControllerOwnershipTest {

    @TempDir
    Path dir;

    private final TestCallers callers = new TestCallers();
    private final InMemoryAgentSessionRepository sessions = new InMemoryAgentSessionRepository();
    private FileStore store;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        store = new FilesystemFileStore(dir, new Namespace("test"));
        var controller = new SessionFilesApiController(store, null, new SessionAccess(sessions));
        mvc = MockMvcBuilders.standaloneSetup(controller).setCustomArgumentResolvers(callers.resolver()).build();
    }

    @Test
    void someoneElsesSessionIsNotFoundAndNothingIsStored() throws Exception {
        String bobs = session("bob");
        StoredFile alicesFile = FilesApiControllerOwnershipTest.saved(store, "a.txt", "alice");
        callers.actAs("alice");

        mvc.perform(get("/api/sessions/{id}/files", bobs)).andExpect(status().isNotFound());
        mvc.perform(delete("/api/sessions/{id}/files", bobs).param("file", "a.txt"))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/sessions/{id}/files", bobs).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fileId\":\"" + alicesFile.id().value() + "\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(multipart("/api/sessions/{id}/files", bobs).file(new MockMultipartFile(
                        "file", "planted.txt", "text/plain", "x".getBytes(StandardCharsets.UTF_8))))
                .andExpect(status().isNotFound());

        assertThat(store.list()).containsExactly(alicesFile);
    }

    @Test
    void onlyAFileTheCallerMayReadIsAttachedById() throws Exception {
        String alices = session("alice");
        StoredFile bobsFile = FilesApiControllerOwnershipTest.saved(store, "b.txt", "bob");
        callers.actAs("alice");

        mvc.perform(post("/api/sessions/{id}/files", alices).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fileId\":\"" + bobsFile.id().value() + "\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/sessions/{id}/files", alices).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fileId\":\"file-0123456789abcdef0123\"}"))
                .andExpect(status().isBadRequest());
    }

    private String session(String owner) {
        AgentSession session = sessions.save(AgentSession.start(AgentId.of("default-chat"), UserId.of(owner),
                ConversationId.random()));
        return session.id().value();
    }
}
