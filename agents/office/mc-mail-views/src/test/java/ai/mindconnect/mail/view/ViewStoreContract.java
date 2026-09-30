package ai.mindconnect.mail.view;

import ai.mindconnect.agent.UserId;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What every {@link ViewStore} does, whatever it keeps the views in: read back
 * the same, listed newest first by kind, one user's never another's.
 */
abstract class ViewStoreContract {

    /** A store with nothing in it yet. */
    protected abstract ViewStore store();

    @Test
    void a_view_comes_back_as_it_was_saved_with_its_state_and_data() {
        ViewStore store = store();
        UserId me = UserId.of("me");
        ViewId id = ViewId.saved();
        StoredView saved = new StoredView(id, "agent", me, "Werbung",
                new ViewState(Set.of("email.privat:7"), "Swisscom", 2, Map.of("sessionId", "s-1")),
                Map.of("entries", "email.privat\tINBOX\t7\n"), Instant.parse("2026-09-23T10:00:00Z"));

        store.save(saved);

        assertThat(store.load(me, id)).contains(saved);
        // Somebody else's list is nobody's.
        assertThat(store.load(UserId.of("you"), id)).isEmpty();
        assertThat(store.list(UserId.of("you"), null)).isEmpty();
    }

    @Test
    void a_folder_view_keeps_only_its_state_and_is_listed_under_its_kind() {
        ViewStore store = store();
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
    void saving_a_view_again_replaces_it() {
        ViewStore store = store();
        UserId me = UserId.of("me");
        StoredView first = new StoredView(ViewId.saved(), "agent", me, "Werbung", ViewState.EMPTY, Map.of(),
                Instant.parse("2026-09-23T10:00:00Z"));

        store.save(first);
        store.save(first.titled("Reklame"));

        assertThat(store.list(me, null)).extracting(StoredView::title).containsExactly("Reklame");
    }

    @Test
    void a_user_and_a_view_with_slashes_in_their_names_stay_apart() {
        ViewStore store = store();
        UserId slashed = UserId.of("a/f");
        UserId plain = UserId.of("a");
        store.save(new StoredView(ViewId.of("x"), "agent", slashed, "one", ViewState.EMPTY, Map.of(), null));
        store.save(new StoredView(ViewId.of("f/x"), "agent", plain, "other", ViewState.EMPTY, Map.of(), null));

        assertThat(store.load(slashed, ViewId.of("x")).orElseThrow().title()).isEqualTo("one");
        assertThat(store.load(plain, ViewId.of("f/x")).orElseThrow().title()).isEqualTo("other");
        assertThat(store.list(slashed, null)).extracting(StoredView::title).containsExactly("one");
    }

    @Test
    void nothing_saved_is_nothing_found() {
        ViewStore store = store();

        assertThat(store.load(UserId.of("me"), ViewId.allInboxes())).isEmpty();
        assertThat(store.list(UserId.of("me"), null)).isEqualTo(List.of());
        store.delete(UserId.of("me"), ViewId.allInboxes());   // nothing to delete is no error
    }
}
