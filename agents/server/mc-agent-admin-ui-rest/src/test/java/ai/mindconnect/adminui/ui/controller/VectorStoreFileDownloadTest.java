package ai.mindconnect.adminui.ui.controller;

import ai.mindconnect.agent.Namespace;
import ai.mindconnect.agent.UserId;
import ai.mindconnect.filestore.StoredFile;
import ai.mindconnect.filestore.filesystem.FilesystemFileStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.StandardClaimNames;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;

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

    @Test
    void theSignedInUserDownloadsTheirOwnFilesButNotSomebodyElses() throws Exception {
        FilesystemFileStore store = new FilesystemFileStore(dir, new Namespace("test"));
        VectorStoreUiController controller = new VectorStoreUiController(null, null, store, null);
        StoredFile alices = store.save("notes.txt", "text/plain", text("hello"), UserId.of("alice"));
        StoredFile legacy = store.save("old.txt", "text/plain", text("before creators"), null);

        ResponseEntity<InputStreamResource> own = controller.downloadStoredFile(alices.id().value(), user("alice"));
        assertThat(own.getStatusCode().value()).isEqualTo(200);
        assertThat(own.getHeaders().getFirst("Content-Disposition")).contains("notes.txt");
        assertThat(body(own)).isEqualTo("hello");

        assertThat(controller.downloadStoredFile(alices.id().value(), user("bob")).getStatusCode().value())
                .isEqualTo(404);
        ResponseEntity<InputStreamResource> old = controller.downloadStoredFile(legacy.id().value(), user("bob"));
        assertThat(old.getStatusCode().value()).isEqualTo(200);
        assertThat(body(old)).isEqualTo("before creators");
        assertThat(controller.downloadStoredFile(UUID.randomUUID().toString(), user("alice")).getStatusCode().value())
                .isEqualTo(404);
    }

    private static InputStream text(String content) {
        return new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
    }

    private static String body(ResponseEntity<InputStreamResource> response) throws Exception {
        try (InputStream in = response.getBody().getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static OidcUser user(String name) {
        Instant now = Instant.now();
        OidcIdToken idToken = OidcIdToken.withTokenValue("id").subject("sub-" + name)
                .claim(StandardClaimNames.PREFERRED_USERNAME, name)
                .issuedAt(now).expiresAt(now.plusSeconds(60)).build();
        return new DefaultOidcUser(AuthorityUtils.createAuthorityList("ROLE_USER"), idToken);
    }
}
