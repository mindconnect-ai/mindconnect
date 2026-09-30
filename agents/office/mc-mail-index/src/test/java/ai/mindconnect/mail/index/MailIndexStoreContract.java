package ai.mindconnect.mail.index;

import ai.mindconnect.agent.UserId;
import ai.mindconnect.mail.Location;
import ai.mindconnect.mail.MailMessage;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What every {@link MailIndexStore} does, whatever it keeps the windows in:
 * a window per user, account and folder, read back whole, one user's never
 * another's.
 */
abstract class MailIndexStoreContract {

    static final UserId ME = UserId.of("me");
    static final Location INBOX = new Location("email.privat", "INBOX");
    static final Location ODD = new Location("email.privat", "Kunden/2026 & Co");

    /** A store with nothing in it yet. */
    protected abstract MailIndexStore store();

    static FolderWindow window(Location at, int heads) {
        List<MailMessage> list = new java.util.ArrayList<>();
        for (int i = heads; i >= 1; i--) {
            list.add(new MailMessage(String.valueOf(i), at, "Mail " + i, "Anna <anna@example.com>",
                    List.of("me@example.com"), Instant.parse("2026-09-23T12:00:00Z").minusSeconds(60L * (heads - i)),
                    i % 2 == 0, false, List.of(), "preview " + i, true));
        }
        return new FolderWindow(at, list, 2_500, Instant.parse("2026-09-23T12:00:00Z"), 1_000);
    }

    @Test
    void a_window_comes_back_whole() {
        MailIndexStore store = store();
        FolderWindow saved = window(INBOX, 3);

        store.save(ME, saved);

        assertThat(store.load(ME, INBOX)).contains(saved);
        assertThat(store.load(UserId.of("you"), INBOX)).isEmpty();
        assertThat(store.load(ME, new Location("email.privat", "Archiv"))).isEmpty();
    }

    @Test
    void saving_a_window_again_replaces_it() {
        MailIndexStore store = store();
        store.save(ME, window(INBOX, 3));
        FolderWindow smaller = window(INBOX, 3).without(List.of("3"));

        store.save(ME, smaller);

        assertThat(store.load(ME, INBOX)).contains(smaller);
        assertThat(store.windows(ME)).containsExactly(INBOX);
    }

    @Test
    void every_window_of_a_user_is_listed_and_a_folder_with_any_name_survives() {
        MailIndexStore store = store();
        store.save(ME, window(INBOX, 2));
        store.save(ME, window(ODD, 2));
        store.save(UserId.of("you"), window(INBOX, 2));

        assertThat(store.windows(ME)).containsExactlyInAnyOrder(INBOX, ODD);
        assertThat(store.load(ME, ODD).orElseThrow().location()).isEqualTo(ODD);

        store.delete(ME, ODD);
        assertThat(store.windows(ME)).containsExactly(INBOX);
        assertThat(store.windows(UserId.of("you"))).containsExactly(INBOX);
    }

    @Test
    void nothing_saved_is_nothing_found() {
        MailIndexStore store = store();

        assertThat(store.load(ME, INBOX)).isEmpty();
        assertThat(store.windows(ME)).isEmpty();
        store.delete(ME, INBOX);   // nothing to delete is no error
    }
}
