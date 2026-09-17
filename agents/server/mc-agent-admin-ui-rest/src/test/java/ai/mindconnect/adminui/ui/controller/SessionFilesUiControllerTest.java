package ai.mindconnect.adminui.ui.controller;

import ai.mindconnect.agent.AgentId;
import ai.mindconnect.agent.UserId;
import ai.mindconnect.agent.runtime.adapter.repo.memory.InMemoryAgentSessionRepository;
import ai.mindconnect.agent.runtime.domain.AgentSession;
import ai.mindconnect.agent.runtime.service.AgentSessionService;
import ai.mindconnect.agentrest.auth.CurrentUserResolver;
import ai.mindconnect.agentrest.auth.CurrentUsers;
import ai.mindconnect.message.domain.ConversationId;
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
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Files dialog hands the session's owner what the agent left in the
 * session's directories — and nobody else anything.
 */
class SessionFilesUiControllerTest {

    @TempDir
    Path tmp;

    private final AtomicReference<UserId> caller = new AtomicReference<>();
    private SessionFilesUiController controller;
    private AgentSession session;
    private String root;

    @BeforeEach
    void setUp() throws Exception {
        Path work = Files.createDirectories(tmp.resolve("work")).toRealPath();
        Files.createDirectories(work.resolve("out"));
        Files.writeString(work.resolve("out/result.csv"), "total\n60\n");
        Files.writeString(work.resolve("page.html"), "<script>alert(1)</script>");
        root = work.toString();

        var sessions = new InMemoryAgentSessionRepository();
        session = sessions.create(AgentSession.start(AgentId.random(), UserId.of("alice"), ConversationId.random())
                .withWorkingDir(root));
        var sessionService = new AgentSessionService(null, sessions, null, null, null, null, null, null);
        controller = new SessionFilesUiController(sessionService, sessions, currentUsers());
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
