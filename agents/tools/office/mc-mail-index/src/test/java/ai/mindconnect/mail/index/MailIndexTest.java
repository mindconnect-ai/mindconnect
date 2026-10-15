package ai.mindconnect.mail.index;

import ai.mindconnect.agent.UserId;
import ai.mindconnect.mail.Fetched;
import ai.mindconnect.mail.Location;
import ai.mindconnect.mail.MailAttachment;
import ai.mindconnect.mail.MailBody;
import ai.mindconnect.mail.MailDraft;
import ai.mindconnect.mail.MailFolder;
import ai.mindconnect.mail.MailMessage;
import ai.mindconnect.mail.MailPage;
import ai.mindconnect.mail.MailQuery;
import ai.mindconnect.mail.MailStore;
import ai.mindconnect.mail.Outcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A folder of 2,500 messages behind a store that counts what it is asked;
 * a window of 1,000 over it.
 */
class MailIndexTest {

    private static final UserId ME = UserId.of("me");
    private static final Location INBOX = new Location("email.privat", "INBOX");
    private static final Instant T0 = Instant.parse("2026-09-23T12:00:00Z");

    private final CountingStore mail = new CountingStore(2_500);
    private final InMemoryMailIndexStore kept = new InMemoryMailIndexStore();
    private final MutableClock clock = new MutableClock(T0);
    private final MailIndex index = new MailIndex(kept, clock, MailIndex.WINDOW, MailIndex.TTL);

    @Test
    void the_first_look_fills_the_window_in_pages_and_the_next_pages_come_from_it() {
        MailIndex.Slice first = index.page(ME, mail, INBOX, 0, 25, null);

        assertThat(first.messages()).hasSize(25);
        assertThat(first.messages().get(0).id()).isEqualTo("2500");   // newest first
        assertThat(first.total()).isEqualTo(2_500);
        assertThat(first.fetched().get(0).freshness()).isEqualTo(Fetched.Freshness.CACHED);
        assertThat(mail.lists).isEqualTo(5);   // 1,000 heads in pages of 200

        MailIndex.Slice page4 = index.page(ME, mail, INBOX, 75, 25, null);
        assertThat(page4.messages().get(0).id()).isEqualTo("2425");
        assertThat(mail.lists).isEqualTo(5);   // no provider call for page 4
        assertThat(kept.load(ME, INBOX).orElseThrow().size()).isEqualTo(1_000);
    }

    @Test
    void the_page_right_below_the_window_is_read_live_and_the_window_grows_to_it() {
        index.page(ME, mail, INBOX, 0, 25, null);
        int before = mail.lists;

        MailIndex.Slice next = index.page(ME, mail, INBOX, 1_000, 25, null);

        assertThat(next.messages().get(0).id()).isEqualTo("1500");
        assertThat(next.fetched().get(0).freshness()).isEqualTo(Fetched.Freshness.LIVE);
        assertThat(mail.lists).isEqualTo(before + 1);
        assertThat(kept.load(ME, INBOX).orElseThrow().size()).isEqualTo(1_025);
        // And from the window now, at the place in the folder it came from.
        assertThat(index.page(ME, mail, INBOX, 1_000, 25, null).messages().get(0).id()).isEqualTo("1500");
        assertThat(mail.lists).isEqualTo(before + 1);
    }

    @Test
    void a_page_further_down_is_read_live_and_leaves_the_window_alone() {
        index.page(ME, mail, INBOX, 0, 25, null);

        MailIndex.Slice deep = index.page(ME, mail, INBOX, 1_200, 25, null);

        assertThat(deep.messages().get(0).id()).isEqualTo("1300");
        assertThat(deep.fetched().get(0).freshness()).isEqualTo(Fetched.Freshness.LIVE);
        // Appended, those 25 would have sat at places 1,000-1,024 of the
        // window, and the page there would have been messages 1300-1276
        // instead of 1500-1476.
        assertThat(kept.load(ME, INBOX).orElseThrow().size()).isEqualTo(1_000);
        assertThat(index.page(ME, mail, INBOX, 1_000, 25, null).messages().get(0).id()).isEqualTo("1500");
    }

    @Test
    void a_stale_window_compares_its_top_with_the_provider_and_takes_what_is_new_or_gone() {
        index.page(ME, mail, INBOX, 0, 25, null);
        mail.arrive("2501", "A new one");
        mail.delete("2499");
        mail.markSeen("2498");
        assertThat(index.page(ME, mail, INBOX, 0, 3, null).messages().get(0).id()).isEqualTo("2500");   // within TTL: as it was

        clock.advance(Duration.ofMinutes(3));
        MailIndex.Slice fresh = index.page(ME, mail, INBOX, 0, 3, null);

        assertThat(fresh.messages()).extracting(MailMessage::id).containsExactly("2501", "2500", "2498");
        assertThat(fresh.messages().get(2).seen()).isTrue();
        assertThat(fresh.total()).isEqualTo(2_500);   // one came, one went
        assertThat(kept.load(ME, INBOX).orElseThrow().has("2499")).isFalse();
    }

    @Test
    void a_folder_the_provider_renamed_throughout_is_filled_again() {
        index.page(ME, mail, INBOX, 0, 25, null);
        // What an IMAP server does when it changes the folder's UIDVALIDITY:
        // the same messages, every one under another id.
        mail.rename(id -> "7-" + id);
        clock.advance(Duration.ofMinutes(3));

        MailIndex.Slice fresh = index.page(ME, mail, INBOX, 0, 3, null);

        assertThat(fresh.messages()).extracting(MailMessage::id).containsExactly("7-2500", "7-2499", "7-2498");
        FolderWindow w = kept.load(ME, INBOX).orElseThrow();
        // Merged, only the newest page would have been renamed, and the 800
        // below it would have gone on answering to ids that name nothing.
        assertThat(w.size()).isEqualTo(1_000);
        assertThat(w.heads()).allMatch(h -> h.id().startsWith("7-"));
    }

    @Test
    void a_search_runs_over_the_window_and_says_how_far_it_reached() {
        MailIndex.Found found = index.search(ME, mail, INBOX, new MailQuery(false, "newsletter", null, null, null, null));

        // Every tenth message is a newsletter; 100 of them in the newest 1,000.
        assertThat(found.matches()).hasSize(100);
        assertThat(found.coverage().searched()).isEqualTo(1_000);
        assertThat(found.coverage().total()).isEqualTo(2_500);
        assertThat(found.coverage().unsearched()).isEqualTo(1_500);
        assertThat(found.coverage().whole()).isFalse();
        assertThat(found.coverage().oldestAt()).isEqualTo(mail.receivedAt(1_501));

        MailIndex.Found bySender = index.search(ME, mail, INBOX, new MailQuery(true, null, "InfoQ", null, null, null));
        assertThat(bySender.matches()).allMatch(m -> m.from().contains("InfoQ") && !m.seen());
    }

    @Test
    void what_the_person_did_is_in_the_window_at_once() {
        index.page(ME, mail, INBOX, 0, 25, null);

        index.removed(ME, INBOX, List.of("2500", "2499"));
        index.seen(ME, INBOX, "2498", true);
        Location archive = new Location("email.privat", "Archiv");
        index.arrived(ME, archive, mail.message(2500).at(archive));   // no window there: nothing to do

        FolderWindow w = kept.load(ME, INBOX).orElseThrow();
        assertThat(w.has("2500")).isFalse();
        assertThat(w.total()).isEqualTo(2_498);
        assertThat(w.head("2498").seen()).isTrue();
        assertThat(index.heads(ME, INBOX, List.of("2498", "2500", "1"))).containsOnlyKeys("2498");
        assertThat(index.has(ME, archive)).isFalse();

        index.forget(ME, INBOX);
        assertThat(index.has(ME, INBOX)).isFalse();
    }

    @Test
    void changes_written_through_at_once_are_all_kept(@TempDir Path dir) throws Exception {
        FileMailIndexStore files = new FileMailIndexStore(dir, "local");
        MailIndex onDisk = new MailIndex(files, clock, 300, MailIndex.TTL);
        onDisk.page(ME, mail, INBOX, 0, 10, null);

        // Fifty removals at once, each a read-change-write of the same file:
        // without the lock each saved the window as it had read it, and
        // most of the removals were lost; with one shared .tmp the writers
        // also wrote into each other's file.
        List<Thread> writers = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            String id = String.valueOf(2_500 - i);
            writers.add(Thread.ofVirtual().start(() -> onDisk.removed(ME, INBOX, List.of(id))));
        }
        for (Thread writer : writers) writer.join();

        FolderWindow w = files.load(ME, INBOX).orElseThrow();
        assertThat(w.size()).isEqualTo(250);
        assertThat(w.total()).isEqualTo(2_450);
        try (var left = java.nio.file.Files.list(dir.resolve("local/mail-index/me/email.privat"))) {
            assertThat(left.map(f -> f.getFileName().toString())).containsExactly("INBOX.json");
        }
    }

    @Test
    void a_window_survives_the_file_store_whole(@TempDir Path dir) {
        FileMailIndexStore files = new FileMailIndexStore(dir, "local");
        MailIndex onDisk = new MailIndex(files, clock, 300, MailIndex.TTL);
        onDisk.page(ME, mail, new Location("email.privat", "Kunden/2026 & Co"), 0, 10, null);

        FolderWindow read = files.load(ME, new Location("email.privat", "Kunden/2026 & Co")).orElseThrow();
        assertThat(read.size()).isEqualTo(300);
        assertThat(read.total()).isEqualTo(2_500);
        assertThat(read.heads().get(0).receivedAt()).isEqualTo(mail.receivedAt(2_500));
        assertThat(files.windows(ME)).containsExactly(new Location("email.privat", "Kunden/2026 & Co"));
        files.delete(ME, new Location("email.privat", "Kunden/2026 & Co"));
        assertThat(files.windows(ME)).isEmpty();
    }

    // ── fixtures ────────────────────────────────────────────────────────────

    /** Ticks forward when a test says so. */
    static final class MutableClock extends Clock {
        private Instant now;
        MutableClock(Instant start) { this.now = start; }
        void advance(Duration by) { now = now.plus(by); }
        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    /** {@code n} messages, id 1..n, newest = n; counts its list calls. */
    static final class CountingStore implements MailStore {
        private final List<MailMessage> messages = new ArrayList<>();
        int lists;

        CountingStore(int n) {
            for (int i = 1; i <= n; i++) messages.add(build(i, i % 10 == 0 ? "Weekly newsletter " + i : "Mail " + i, false));
        }

        MailMessage build(int i, String subject, boolean seen) {
            return new MailMessage(String.valueOf(i), INBOX, subject,
                    i % 10 == 0 ? "InfoQ <newsletter@infoq.com>" : "Anna <anna@example.com>",
                    List.of("me@example.com"), receivedAt(i), seen, false, List.of(), "preview " + i, true);
        }

        Instant receivedAt(int i) { return T0.minus(Duration.ofMinutes(3_000L - i)); }

        MailMessage message(int i) { return messages.stream().filter(m -> m.id().equals(String.valueOf(i))).findFirst().orElseThrow(); }

        void arrive(String id, String subject) { messages.add(build(Integer.parseInt(id), subject, false)); }
        void delete(String id) { messages.removeIf(m -> m.id().equals(id)); }
        void markSeen(String id) { messages.replaceAll(m -> m.id().equals(id) ? m.withSeen(true) : m); }
        void rename(java.util.function.UnaryOperator<String> name) {
            messages.replaceAll(m -> new MailMessage(name.apply(m.id()), m.location(), m.subject(), m.from(), m.to(),
                    m.receivedAt(), m.seen(), m.hasAttachments(), m.attachments(), m.body(), m.truncated()));
        }

        @Override public MailPage list(String folderId, int skip, int limit, MailQuery query) {
            lists++;
            List<MailMessage> sorted = new ArrayList<>(messages);
            sorted.sort(FolderWindow.NEWEST_FIRST);
            return MailPage.of(sorted.stream().skip(skip).limit(limit).toList(), sorted.size());
        }
        @Override public List<MailFolder> folders() { return List.of(MailFolder.of("INBOX", "Inbox")); }
        @Override public MailMessage read(String folderId, String messageId) { return message(Integer.parseInt(messageId)); }
        @Override public MailBody body(String folderId, String messageId) { return MailBody.plain(""); }
        @Override public void setSeen(String folderId, String messageId, boolean seen) { }
        @Override public boolean canOrganise() { return true; }
        @Override public List<Outcome> delete(String folderId, List<String> ids) { return List.of(); }
        @Override public List<Outcome> move(String folderId, List<String> ids, String target) { return List.of(); }
        @Override public void restore(String folderId, List<String> receipt) { }
        @Override public boolean canSend() { return false; }
        @Override public String send(MailDraft draft) { return null; }
        @Override public List<MailAttachment> attachments(String folderId, String messageId) { return List.of(); }
        @Override public void close() { }
    }
}
