package ai.mindconnect.adminui;

import ai.mindconnect.agent.domain.AgentDefinition;
import ai.mindconnect.agent.domain.AgentSession;
import ai.mindconnect.agent.port.out.AgentDefinitionRepository;
import ai.mindconnect.agent.port.out.AgentSessionRepository;
import ai.mindconnect.agent.service.AgentSessionService;
import ai.mindconnect.agentrest.service.SessionFileService;
import ai.mindconnect.common.Namespace;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The chat UI's file-upload path end to end, through the REAL app: multipart
 * {@code POST /chat/api/sessions/{id}/chat-files} → file store → ingestion
 * into the session's vector store → {@code vector_search} activation +
 * attached-file note on the session. Complements the runtime-level
 * {@code RuntimeFileQaExampleTest}, which covers attach+ask via the builder
 * but never touches the server controllers.
 *
 * <p>Ingestion needs a live EMBEDDINGS model — the test SKIPS (assumption)
 * when LM Studio is not running or no embeddings model is loaded, so a clean
 * build never fails for a missing model server.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "mindconnect.encryption.secret-key=test-key-32-characters-long-abcd",
        "mindconnect.auth.enabled=false"
})
class ChatFileUploadSmokeTest {

    private static final String LM_STUDIO =
            System.getenv().getOrDefault("LM_STUDIO_URL", "http://localhost:1234");

    /**
     * Fresh dirs per test run — the app seeds its initial data into them.
     * Vector stores and workflows have their OWN properties (defaulting to
     * the relative {@code data/…}); missing one here would write test junk
     * into a real data directory.
     */
    @DynamicPropertySource
    static void tempDataDir(DynamicPropertyRegistry registry) {
        try {
            var dir = Files.createTempDirectory("mc-upload-test");
            registry.add("mindconnect.data.base-dir", dir::toString);
            registry.add("mindconnect.tools.base-dir", dir::toString);
            registry.add("mindconnect.vector-store.dir", () -> dir.resolve("vector-stores").toString());
            registry.add("mindconnect.workflow-admin.dir", () -> dir.resolve("workflows").toString());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Autowired TestRestTemplate rest;
    @Autowired AgentDefinitionRepository agents;
    @Autowired AgentSessionRepository sessions;
    @Autowired AgentSessionService sessionService;
    @Autowired SessionFileService sessionFiles;
    @Autowired Namespace namespace;

    /** True when LM Studio answers and has a loaded embeddings model. */
    static boolean embeddingsUp() {
        try {
            HttpResponse<String> response = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(2)).build()
                    .send(HttpRequest.newBuilder(URI.create(LM_STUDIO + "/api/v0/models"))
                                    .timeout(Duration.ofSeconds(3)).GET().build(),
                            HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200
                    && response.body().contains("\"embeddings\"")
                    && response.body().contains("\"loaded\"");
        } catch (Exception e) {
            return false;
        }
    }

    @Test
    void uploadIngestsIntoTheSessionStoreAndAnnouncesTheFile() throws Exception {
        assumeTrue(embeddingsUp(),
                "LM Studio is not running at " + LM_STUDIO + " or no embeddings model is loaded");

        // Any seeded agent will do — the upload path is agent-agnostic.
        AgentDefinition agent = agents.findByNamespace(namespace).stream().findFirst().orElseThrow();
        AgentSession session = sessionService.openChat(agent.id(), namespace, "upload-tester");

        String text = "MindConnect upload smoke test.\n"
                + "The secret ingredient of the test soup is paprika.\n".repeat(40);
        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("chat-attach", new ByteArrayResource(text.getBytes(StandardCharsets.UTF_8)) {
            @Override public String getFilename() { return "soup-notes.md"; }
        });
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);

        ResponseEntity<String> response = rest.postForEntity(
                "/chat/api/sessions/" + session.id() + "/chat-files",
                new HttpEntity<>(form, headers), String.class);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        // The endpoint answers 200 with an error TOAST on failure — the
        // response must carry the success message, not an attach error.
        Map<?, ?> patch = new ObjectMapper().readValue(response.getBody(), Map.class);
        assertThat(response.getBody())
                .as("attach result toast: %s", response.getBody())
                .contains("attached");
        assertThat(patch).isNotEmpty();

        Map<String, Long> attachments = sessionFiles.listAttachments(session.id());
        assertThat(attachments).as("one ingested file with chunks").hasSize(1);
        assertThat(attachments.values().iterator().next()).isGreaterThan(0L);

        AgentSession reloaded = sessions.findById(session.id()).orElseThrow();
        assertThat(reloaded.attachedFiles())
                .as("the system prompt announces the file")
                .contains("soup-notes.md");
    }
}
