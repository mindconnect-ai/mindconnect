package ai.mindconnect.agentrest.controller;

import ai.mindconnect.agent.Namespace;
import ai.mindconnect.agent.UserId;
import ai.mindconnect.filestore.FileStore;
import ai.mindconnect.filestore.StoredFile;
import ai.mindconnect.filestore.filesystem.FilesystemFileStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A file is its uploader's: listed for them, read and deleted by them. A file
 * stored before uploaders were recorded stays readable by id, but is neither
 * listed nor deletable.
 */
class FilesApiControllerOwnershipTest {

    @TempDir
    Path dir;

    private final TestCallers callers = new TestCallers();
    private FileStore store;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        store = new FilesystemFileStore(dir, new Namespace("test"));
        mvc = MockMvcBuilders.standaloneSetup(new FilesApiController(store))
                .setCustomArgumentResolvers(callers.resolver()).build();
    }

    @Test
    void anUploadIsTheCallersAndTheListHoldsOnlyTheirOwn() throws Exception {
        callers.actAs("alice");
        mvc.perform(multipart("/api/files").file(
                        new MockMultipartFile("file", "a.txt", "text/plain", bytes("a"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.creator").value("alice"));
        StoredFile bobs = store.save("b.txt", "text/plain", in("b"), UserId.of("bob"));
        store.save("old.txt", "text/plain", in("old"));

        mvc.perform(get("/api/files"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].name").value("a.txt"));

        callers.actAs("bob");
        mvc.perform(get("/api/files"))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(bobs.id().value()));
    }

    @Test
    void someoneElsesFileIsNotFound() throws Exception {
        StoredFile bobs = store.save("b.txt", "text/plain", in("b"), UserId.of("bob"));
        String id = bobs.id().value();

        callers.actAs("alice");
        mvc.perform(get("/api/files/{id}", id)).andExpect(status().isNotFound());
        mvc.perform(get("/api/files/{id}/content", id)).andExpect(status().isNotFound());
        mvc.perform(delete("/api/files/{id}", id)).andExpect(status().isNotFound());
        assertThat(store.find(bobs.id())).isPresent();

        callers.actAs("bob");
        mvc.perform(get("/api/files/{id}/content", id))
                .andExpect(status().isOk())
                .andExpect(content().string("b"));
        mvc.perform(delete("/api/files/{id}", id)).andExpect(status().isNoContent());
        assertThat(store.find(bobs.id())).isEmpty();
    }

    @Test
    void aFileFromBeforeCreatorsIsReadableByIdButNeitherListedNorDeletable() throws Exception {
        StoredFile old = store.save("old.txt", "text/plain", in("old"));
        String id = old.id().value();

        callers.actAs("alice");
        mvc.perform(get("/api/files/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("old.txt"));
        mvc.perform(get("/api/files/{id}/content", id))
                .andExpect(status().isOk())
                .andExpect(content().string("old"));
        mvc.perform(get("/api/files")).andExpect(jsonPath("$").isEmpty());
        mvc.perform(delete("/api/files/{id}", id)).andExpect(status().isNotFound());
        assertThat(store.find(old.id())).isPresent();
    }

    @Test
    void anUnknownIdIsNotFound() throws Exception {
        callers.actAs("alice");

        mvc.perform(get("/api/files/{id}", "file-0123456789abcdef0123")).andExpect(status().isNotFound());
        mvc.perform(delete("/api/files/{id}", "file-0123456789abcdef0123")).andExpect(status().isNotFound());
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static InputStream in(String text) {
        return new ByteArrayInputStream(bytes(text));
    }

    static StoredFile saved(FileStore store, String name, String owner) throws IOException {
        return store.save(name, "text/plain", in(name), UserId.of(owner));
    }
}
