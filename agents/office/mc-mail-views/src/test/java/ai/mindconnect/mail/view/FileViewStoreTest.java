package ai.mindconnect.mail.view;

import ai.mindconnect.agent.Namespace;
import ai.mindconnect.agent.UserId;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
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
    void views_saved_at_once_are_all_kept() throws Exception {
        FileViewStore store = store();
        UserId me = UserId.of("me");

        // Every save reads the user's one file, adds its view and writes the
        // lot back. Without a lock around that, saves at the same moment each
        // wrote the file as they had read it and all but one view was lost —
        // and sharing one .tmp, they also wrote into each other's file.
        List<Thread> savers = new java.util.ArrayList<>();
        for (int i = 0; i < 40; i++) {
            StoredView view = new StoredView(ViewId.saved(), "agent", me, "List " + i, ViewState.EMPTY, Map.of(),
                    Instant.parse("2026-09-23T10:00:00Z").plusSeconds(i));
            savers.add(Thread.ofVirtual().start(() -> store.save(view)));
        }
        for (Thread saver : savers) saver.join();

        assertThat(store.list(me, "agent")).hasSize(40);
        try (var files = Files.list(dir.resolve("local/mail-views"))) {
            assertThat(files.map(f -> f.getFileName().toString())).containsExactly("me.json");
        }
    }

    @Test
    void nothing_saved_writes_no_file() {
        store().load(UserId.of("me"), ViewId.allInboxes());
        store().list(UserId.of("me"), null);

        assertThat(Files.exists(dir.resolve("local"))).isFalse();
    }
}
