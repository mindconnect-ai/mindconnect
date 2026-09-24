package ai.mindconnect.mail.view;

import ai.mindconnect.agent.Namespace;
import ai.mindconnect.agent.UserId;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Views in a file per user: written whole, read back the same, one user's never another's. */
class FileViewStoreTest extends ViewStoreContract {

    @TempDir
    Path dir;

    @Override
    protected FileViewStore store() {
        return new FileViewStore(() -> dir, () -> new Namespace("local"), new ObjectMapper().findAndRegisterModules());
    }

    @Test
    void a_users_views_are_one_file_under_the_namespace() {
        store().save(new StoredView(ViewId.saved(), "agent", UserId.of("me"), "Werbung", ViewState.EMPTY,
                Map.of(), null));

        assertThat(Files.exists(dir.resolve("local/mail-views/me.json"))).isTrue();
    }

    @Test
    void nothing_saved_writes_no_file() {
        store().load(UserId.of("me"), ViewId.allInboxes());
        store().list(UserId.of("me"), null);

        assertThat(Files.exists(dir.resolve("local"))).isFalse();
    }
}
