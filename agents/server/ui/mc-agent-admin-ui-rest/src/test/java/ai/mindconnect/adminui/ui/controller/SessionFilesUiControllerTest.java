package ai.mindconnect.adminui.ui.controller;

import ai.mindconnect.agent.scope.Namespace;
import ai.mindconnect.agent.scope.ScopeSupplier;
import ai.mindconnect.agent.UserId;
import ai.mindconnect.agent.management.RepositorySessionManagement;
import ai.mindconnect.agent.management.adapter.InMemoryAgentRepository;
import ai.mindconnect.agent.management.api.ManagedSession;
import ai.mindconnect.agent.management.api.SessionDirectoryPolicy;
import ai.mindconnect.agent.management.api.SessionManagement;
import ai.mindconnect.agent.management.directory.UserHome;
import ai.mindconnect.agent.runtime.api.domain.AgentDefinition;
import ai.mindconnect.agent.runtime.api.domain.ModelSettings;
import ai.mindconnect.agent.runtime.api.domain.Session;
import ai.mindconnect.agent.runtime.store.InMemorySessionRepository;
import ai.mindconnect.agent.runtime.store.RepositorySessionStore;
import ai.mindconnect.agent.sdk.AgentRuntime;
import ai.mindconnect.message.adapter.memory.InMemoryMessageRepository;
import ai.mindconnect.plugin.DefaultPluginRegistry;
import ai.mindconnect.plugin.PluginRegistry;
import ai.mindconnect.agentrest.auth.CurrentUserResolver;
import ai.mindconnect.agentrest.auth.CurrentUsers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Files dialog hands the session's owner what the agent left in the
 * session's directories — and nobody else anything.
 */
class SessionFilesUiControllerTest {

    private static final AgentDefinition HELPER = new AgentDefinition("helper", "Helps.", "You help.",
            ModelSettings.NONE, List.of(), 5, Map.of());

    @TempDir
    Path tmp;

    private final AtomicReference<UserId> caller = new AtomicReference<>();
    private SessionFilesUiController controller;
    private ManagedSession session;
    private String root;

    @BeforeEach
    void setUp() throws Exception {
        Path work = Files.createDirectories(tmp.resolve("work")).toRealPath();
        Files.createDirectories(work.resolve("out"));
        Files.writeString(work.resolve("out/result.csv"), "total\n60\n");
        Files.writeString(work.resolve("page.html"), "<script>alert(1)</script>");
        root = work.toString();

        var sessions = new InMemorySessionRepository(ScopeSupplier.fixed(new Namespace("test")));
        var messages = new InMemoryMessageRepository(ScopeSupplier.fixed(new Namespace("test")));
        SessionManagement management = new RepositorySessionManagement(sessions, messages,
                new InMemoryAgentRepository(ScopeSupplier.fixed(new Namespace("test"))), SessionDirectoryPolicy.unrestricted(),
                List.of(),
                new ai.mindconnect.agent.management.SessionLifecycle(sessions, session -> java.util.Optional.empty()));
        var id = ai.mindconnect.agent.SessionId.of("s1");
        new RepositorySessionStore(sessions, messages).create(new Session(id,
                UserId.of("alice"), null, HELPER,
                Map.of(ManagedSession.WORKING_DIRECTORY, root), Instant.parse("2026-11-01T00:00:00Z")));
        session = management.find(id).orElseThrow();

        PluginRegistry registry = new DefaultPluginRegistry();
        registry.provide(SessionManagement.class, () -> management);
        registry.start();
        var beans = new StaticListableBeanFactory();
        controller = new SessionFilesUiController(new AgentRuntime(registry, () -> { }), currentUsers(), UserHome.none(),
                beans.getBeanProvider(ai.mindconnect.agent.tool.workspace.WorkspaceProvider.class));
    }

    @Test
    void theOwnerDownloadsAFileTheAgentWrote() throws Exception {
        actAs("alice");

        ResponseEntity<InputStreamResource> response =
                controller.content(session.id().value(), root, "out/result.csv", true);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getHeaders().getFirst("Content-Disposition")).startsWith("attachment").contains("result.csv");
        assertThat(body(response)).isEqualTo("total\n60\n");
    }

    @Test
    void htmlIsViewedAsItsSource() throws Exception {
        actAs("alice");

        ResponseEntity<InputStreamResource> response = controller.content(session.id().value(), root, "page.html", false);

        assertThat(response.getHeaders().getContentType().toString()).startsWith("text/plain");
        assertThat(response.getHeaders().getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(body(response)).contains("<script>");
    }

    @Test
    void theOwnerBrowsesTheSessionsDirectories() {
        actAs("alice");

        assertThat(controller.browse(session.id().value(), false).getStatusCode().value()).isEqualTo(200);
        assertThat(controller.browse(session.id().value(), true).getStatusCode().value()).isEqualTo(200);
        assertThat(controller.folder(session.id().value(), root, "out").getStatusCode().value()).isEqualTo(200);
        assertThat(controller.folder(session.id().value(), root, "..").getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void somebodyElseFindsNothing() throws Exception {
        actAs("bob");

        assertThat(controller.browse(session.id().value(), true).getStatusCode().value()).isEqualTo(404);
        assertThat(controller.folder(session.id().value(), root, "out").getStatusCode().value()).isEqualTo(404);
        assertThat(controller.content(session.id().value(), root, "out/result.csv", true).getStatusCode().value())
                .isEqualTo(404);
    }

    @Test
    void aPathOutOfTheDirectoriesIsNotFound() throws Exception {
        Files.writeString(tmp.resolve("secret.txt"), "not yours");
        actAs("alice");

        assertThat(controller.content(session.id().value(), root, "../secret.txt", true).getStatusCode().value())
                .isEqualTo(404);
        assertThat(controller.content(session.id().value(), tmp.toRealPath().toString(), "secret.txt", true)
                .getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void theOwnerDownloadsAFolderAsAZip() throws Exception {
        actAs("alice");
        var response = new MockHttpServletResponse();

        controller.zip(session.id().value(), root, "out", response);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentType()).isEqualTo("application/zip");
        assertThat(response.getHeader("Content-Disposition")).startsWith("attachment").contains("out.zip");
        try (var zip = new ZipInputStream(new ByteArrayInputStream(response.getContentAsByteArray()))) {
            assertThat(zip.getNextEntry().getName()).isEqualTo("out/");
            assertThat(zip.getNextEntry().getName()).isEqualTo("out/result.csv");
            assertThat(new String(zip.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("total\n60\n");
        }
    }

    @Test
    void nobodyElseZipsAFolder_andNothingOutsideIsZipped() throws Exception {
        actAs("bob");
        var asBob = new MockHttpServletResponse();
        controller.zip(session.id().value(), root, "out", asBob);
        assertThat(asBob.getStatus()).isEqualTo(404);

        actAs("alice");
        var outside = new MockHttpServletResponse();
        controller.zip(session.id().value(), root, "..", outside);
        assertThat(outside.getStatus()).isEqualTo(404);
    }

    @Test
    void theOwnerOpensAFile_textInAnEditor_anImageAsAPicture_anOfficeFileOnlyToDownload() throws Exception {
        Files.write(Path.of(root, "out/chart.png"), new byte[]{(byte) 0x89, 'P', 'N', 'G', 0});
        Files.writeString(Path.of(root, "out/deck.pptx"), "PK looks like text");
        actAs("alice");

        String office = json(controller.view(session.id().value(), root, "out/deck.pptx").getBody());
        assertThat(office).doesNotContain("\"files-editor\"").contains("Download it to open it");

        String text = json(controller.view(session.id().value(), root, "out/result.csv").getBody());
        assertThat(text).contains("\"files-editor\"").contains("total\\n60\\n").contains("\"files-viewer\"");

        String image = json(controller.view(session.id().value(), root, "out/chart.png").getBody());
        assertThat(image).contains("\"files-viewer-image\"").contains("/content?root=").doesNotContain("\"files-editor\"");

        String gone = json(controller.view(session.id().value(), root, "../secret.txt").getBody());
        assertThat(gone).contains("The file is gone.");
    }

    @Test
    void theOwnerSavesAFile_andDeletesFilesAndFolders() throws Exception {
        actAs("alice");

        var saved = controller.save(session.id().value(), root, "out/result.csv", java.util.Map.of("content", "total\n70\n"));
        assertThat(json(saved.getBody())).contains("Saved result.csv");
        assertThat(Path.of(root, "out/result.csv")).hasContent("total\n70\n");

        var deleted = controller.delete(session.id().value(), root, "page.html");
        assertThat(json(deleted.getBody())).contains("Deleted page.html");
        assertThat(Path.of(root, "page.html")).doesNotExist();

        controller.delete(session.id().value(), root, "out");
        assertThat(Path.of(root, "out")).doesNotExist();

        assertThat(json(controller.delete(session.id().value(), root, "").getBody())).contains("Nothing to delete");
        assertThat(Path.of(root)).exists();
    }

    @Test
    void nobodyElseOpensSavesOrDeletes() throws Exception {
        actAs("bob");

        assertThat(json(controller.view(session.id().value(), root, "out/result.csv").getBody()))
                .doesNotContain("total");
        assertThat(controller.save(session.id().value(), root, "out/result.csv", java.util.Map.of("content", "x"))
                .getStatusCode().value()).isEqualTo(404);
        assertThat(controller.delete(session.id().value(), root, "out").getStatusCode().value()).isEqualTo(404);
        assertThat(Path.of(root, "out/result.csv")).hasContent("total\n60\n");
    }

    private static String json(Object body) throws Exception {
        return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(body);
    }

    private void actAs(String user) {
        caller.set(UserId.of(user));
    }

    private CurrentUsers currentUsers() {
        var beans = new StaticListableBeanFactory();
        beans.addBean("resolver", (CurrentUserResolver) () -> Optional.ofNullable(caller.get()));
        return new CurrentUsers(beans.getBeanProvider(CurrentUserResolver.class), true, "dev");
    }

    private static String body(ResponseEntity<InputStreamResource> response) throws Exception {
        try (InputStream in = response.getBody().getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
