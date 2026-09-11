package ai.mindconnect.adminui.ui.controller;

import ai.mindconnect.agent.Namespace;
import ai.mindconnect.agent.UserId;
import ai.mindconnect.agentrest.auth.CurrentUserResolver;
import ai.mindconnect.agentrest.auth.CurrentUsers;
import ai.mindconnect.filestore.StoredFile;
import ai.mindconnect.filestore.filesystem.FilesystemFileStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ResponseEntity;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Download button of the vector-store files tab runs on the browser
 * session, since the Files API takes bearer tokens only — and hands the
 * signed-in user exactly what the Files API would: their own files and those
 * stored before creators were recorded, nobody else's.
 */
class VectorStoreFileDownloadTest {

    @TempDir
    Path dir;

    private final AtomicReference<UserId> caller = new AtomicReference<>();

    @Test
    void theSignedInUserDownloadsTheirOwnFilesButNotSomebodyElses() throws Exception {
        FilesystemFileStore store = new FilesystemFileStore(dir, new Namespace("test"));
        VectorStoreUiController controller = new VectorStoreUiController(null, null, store, null, currentUsers(), null);
        StoredFile alices = store.save("notes.txt", "text/plain", text("hello"), UserId.of("alice"));
        StoredFile legacy = store.save("old.txt", "text/plain", text("before creators"), null);

        actAs("alice");
        ResponseEntity<InputStreamResource> own = controller.downloadStoredFile(alices.id().value());
        assertThat(own.getStatusCode().value()).isEqualTo(200);
        assertThat(own.getHeaders().getFirst("Content-Disposition")).contains("notes.txt");
        assertThat(body(own)).isEqualTo("hello");
        assertThat(controller.downloadStoredFile(UUID.randomUUID().toString()).getStatusCode().value())
                .isEqualTo(404);

        actAs("bob");
        assertThat(controller.downloadStoredFile(alices.id().value()).getStatusCode().value()).isEqualTo(404);
        ResponseEntity<InputStreamResource> old = controller.downloadStoredFile(legacy.id().value());
        assertThat(old.getStatusCode().value()).isEqualTo(200);
        assertThat(body(old)).isEqualTo("before creators");
    }

    private void actAs(String user) {
        caller.set(UserId.of(user));
    }

    private CurrentUsers currentUsers() {
        var beans = new StaticListableBeanFactory();
        beans.addBean("resolver", (CurrentUserResolver) () -> Optional.ofNullable(caller.get()));
        return new CurrentUsers(beans.getBeanProvider(CurrentUserResolver.class), true, "dev");
    }

    private static InputStream text(String content) {
        return new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
    }

    private static String body(ResponseEntity<InputStreamResource> response) throws Exception {
        try (InputStream in = response.getBody().getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
