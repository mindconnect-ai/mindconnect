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
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Views in a file per user: written whole, read back the same, one user's never another's. */
class FileViewStoreTest {

    @TempDir
    Path dir;

    private FileViewStore store() {
        return new FileViewStore(() -> dir, () -> new Namespace("local"), new ObjectMapper().findAndRegisterModules());
    }

    @Test
    void a_view_comes_back_as_it_was_saved_with_its_state_and_data() throws Exception {
        FileViewStore store = store();
        UserId me = UserId.of("me");
        ViewId id = ViewId.saved();
        StoredView saved = new StoredView(id, "agent", me, "Werbung",
                new ViewState(Set.of("email.privat:7"), "Swisscom", 2, Map.of("sessionId", "s-1")),
                Map.of("entries", "email.privat\tINBOX\t7\n"), Instant.parse("2026-09-23T10:00:00Z"));

        store.save(saved);

        assertThat(store.load(me, id)).contains(saved);
        assertThat(Files.exists(dir.resolve("local/mail-views/me.json"))).isTrue();
        // Somebody else's list is nobody's.
        assertThat(store.load(UserId.of("you"), id)).isEmpty();
    }

    @Test
    void a_folder_view_keeps_only_its_state_and_is_listed_under_its_kind() {
        FileViewStore store = store();
        UserId me = UserId.of("me");
        ViewId inbox = ViewId.folder("email.privat", "INBOX");

        store.save(new StoredView(inbox, "folder", me, "Posteingang", ViewState.EMPTY.search("Swisscom"),
                Map.of(), Instant.parse("2026-09-23T09:00:00Z")));
        store.save(new StoredView(ViewId.saved(), "agent", me, "Werbung", ViewState.EMPTY, Map.of(),
                Instant.parse("2026-09-23T10:00:00Z")));
        store.save(new StoredView(ViewId.saved(), "agent", me, "Rechnungen", ViewState.EMPTY, Map.of(),
                Instant.parse("2026-09-23T11:00:00Z")));

        assertThat(store.load(me, inbox).orElseThrow().state().query()).isEqualTo("Swisscom");
        // Newest first, and only the kind asked for — what a sidebar wants.
        assertThat(store.list(me, "agent")).extracting(StoredView::title).containsExactly("Rechnungen", "Werbung");
        assertThat(store.list(me, null)).hasSize(3);

        store.delete(me, inbox);
        assertThat(store.load(me, inbox)).isEmpty();
        assertThat(store.list(me, null)).hasSize(2);
    }

    @Test
    void nothing_saved_is_nothing_found_and_no_file() {
        assertThat(store().load(UserId.of("me"), ViewId.allInboxes())).isEmpty();
        assertThat(store().list(UserId.of("me"), null)).isEqualTo(List.of());
        assertThat(Files.exists(dir.resolve("local"))).isFalse();
    }
}
