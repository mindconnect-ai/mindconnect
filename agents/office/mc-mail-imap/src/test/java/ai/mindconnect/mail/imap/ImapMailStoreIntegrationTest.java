package ai.mindconnect.mail.imap;

import ai.mindconnect.mail.MailStoreException;
import ai.mindconnect.mail.MailStore;
import ai.mindconnect.mail.MailPage;
import ai.mindconnect.mail.MailFolder;
import ai.mindconnect.mail.MailDraft;
import ai.mindconnect.mail.MailAccounts;
import ai.mindconnect.mail.ConnectedMailbox;
import ai.mindconnect.agent.UserId;
import ai.mindconnect.agent.tool.Connections;
import ai.mindconnect.agent.tool.ToolConnection;
import ai.mindconnect.mail.MailMessage;
import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.GreenMailUtil;
import com.icegreen.greenmail.util.ServerSetupTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The port against a real IMAP and SMTP server — GreenMail, in-process, no
 * network and no account needed.
 *
 * <p>What is worth testing here is what only means something against a
 * server: that a folder lists, that an id from the listing reads, that a flag
 * survives a reconnect, that a search matches the sender as well as the
 * subject, and that a sent message arrives. The rest of this module is values
 * and is tested as values.
 */
class ImapMailStoreIntegrationTest {

    @RegisterExtension
    static final GreenMailExtension MAIL = new GreenMailExtension(ServerSetupTest.SMTP_IMAP);

    private static final String ADDRESS = "alice@example.com";
    private static final String PASSWORD = "s3cret";
    private static final UserId ALICE = UserId.of("alice");

    private MailAccounts accounts;

    @BeforeEach
    void setUp() {
        MAIL.setUser(ADDRESS, ADDRESS, PASSWORD);
        MAIL.setUser("bob@example.com", "bob@example.com", "bobs-password");
        accounts = new MailAccounts(connections(connection()));
    }

    @Test
    void the_user_sees_the_mailbox_they_connected() {
        List<ConnectedMailbox> mailboxes = accounts.of(ALICE);

        assertThat(mailboxes).singleElement().satisfies(mailbox -> {
            assertThat(mailbox.id()).isEqualTo("email.privat");
            assertThat(mailbox.describe()).isEqualTo("Privat · IMAP");
        });
    }

    @Test
    void a_message_is_listed_and_the_id_from_the_listing_reads_it() {
        deliver("Invoice 4711", "Dear Alice,\n\nthe invoice is due on Friday.\n\nBob");

        try (MailStore store = accounts.open(ALICE, "email.privat")) {
            assertThat(store.folders()).extracting(MailFolder::id).contains("INBOX");

            List<MailMessage> listed = store.list("INBOX", 0, 25, false, null).messages();
            assertThat(listed).singleElement().satisfies(message -> {
                assertThat(message.subject()).isEqualTo("Invoice 4711");
                assertThat(message.from()).contains("bob@example.com");
                assertThat(message.seen()).isFalse();
            });

            MailMessage read = store.read("INBOX", listed.get(0).id());
            assertThat(read.body()).contains("the invoice is due on Friday");
        }
    }

    @Test
    void a_read_flag_is_still_there_after_the_connection_was_closed_and_opened_again() {
        deliver("Reminder", "Friday.");

        String id;
        try (MailStore store = accounts.open(ALICE, "email.privat")) {
            id = store.list("INBOX", 0, 25, false, null).messages().get(0).id();
            store.setSeen("INBOX", id, true);
        }
        // A new store means a new connection: this is what the screen does
        // between two clicks, and the point of not pooling.
        try (MailStore store = accounts.open(ALICE, "email.privat")) {
            assertThat(store.read("INBOX", id).seen()).isTrue();
            assertThat(store.list("INBOX", 0, 25, true, null).messages()).isEmpty();
        }
    }

    @Test
    void a_search_matches_the_sender_or_the_subject_and_not_only_both() {
        deliverFrom("carol@example.com", "Invoice 4711", "due on Friday");
        deliverFrom("bob@example.com", "Forwarding carol@example.com request", "see below");

        try (MailStore store = accounts.open(ALICE, "email.privat")) {
            // IMAP ANDs its search terms, so one SEARCH for this text in both
            // the sender and the subject would find nothing at all: no message
            // has it in both. The store searches twice and merges, so the one
            // it is from and the one that mentions it both come back.
            assertThat(store.list("INBOX", 0, 25, false, "carol@example.com").messages())
                    .extracting(MailMessage::subject)
                    .containsExactlyInAnyOrder("Invoice 4711", "Forwarding carol@example.com request");

            assertThat(store.list("INBOX", 0, 25, false, "Invoice").messages())
                    .extracting(MailMessage::subject).containsExactly("Invoice 4711");
        }
    }

    @Test
    void a_folder_pages_and_says_how_many_there_are() {
        for (int i = 1; i <= 7; i++) {
            deliver("Message " + i, "body " + i);
        }

        try (MailStore store = accounts.open(ALICE, "email.privat")) {
            MailPage first = store.list("INBOX", 0, 3, false, null);
            assertThat(first.total()).isEqualTo(7);
            assertThat(first.counted()).isTrue();
            assertThat(first.messages()).extracting(MailMessage::subject)
                    .containsExactly("Message 7", "Message 6", "Message 5");

            MailPage second = store.list("INBOX", 3, 3, false, null);
            assertThat(second.messages()).extracting(MailMessage::subject)
                    .containsExactly("Message 4", "Message 3", "Message 2");

            MailPage last = store.list("INBOX", 6, 3, false, null);
            assertThat(last.messages()).extracting(MailMessage::subject)
                    .containsExactly("Message 1");

            // Past the end is empty, not an error: a stale page number is a
            // normal thing to be handed after somebody deleted mail elsewhere.
            assertThat(store.list("INBOX", 99, 3, false, null).messages()).isEmpty();
        }
    }

    @Test
    void a_search_pages_without_claiming_a_total_it_cannot_know() {
        deliverFrom("carol@example.com", "Invoice 4711", "due on Friday");
        deliverFrom("bob@example.com", "Forwarding carol@example.com request", "see below");

        try (MailStore store = accounts.open(ALICE, "email.privat")) {
            MailPage page = store.list("INBOX", 0, 1, false, "carol@example.com");

            // Two searches merged: counting both would count twice whatever
            // matched both halves, and finding the overlap means fetching
            // everything — the cost paging exists to avoid.
            assertThat(page.counted()).isFalse();
            assertThat(page.messages()).hasSize(1);
            assertThat(store.list("INBOX", 1, 1, false, "carol@example.com").messages()).hasSize(1);
        }
    }

    @Test
    void a_sent_message_arrives() {
        try (MailStore store = accounts.open(ALICE, "email.privat")) {
            assertThat(store.canSend()).isTrue();

            store.send(new MailDraft(List.of("bob@example.com"), List.of(),
                    "Answer", "Friday works."));
        }
        assertThat(MAIL.waitForIncomingEmail(5_000, 1)).isTrue();
        assertThat(GreenMailUtil.getBody(MAIL.getReceivedMessagesForDomain("bob@example.com")[0]))
                .contains("Friday works.");
    }

    @Test
    void a_sent_message_is_kept_in_the_sent_folder_marked_read() throws Exception {
        // SMTP only delivers; without this the sent folder stayed empty of
        // everything this client sent.
        folder("Sent Messages");

        try (MailStore store = accounts.open(ALICE, "email.privat")) {
            store.send(new MailDraft(List.of("bob@example.com"), List.of(), "Answer", "Friday works."));

            assertThat(store.list("Sent Messages", 0, 10, false, null).messages())
                    .singleElement()
                    .satisfies(m -> {
                        assertThat(m.subject()).isEqualTo("Answer");
                        assertThat(m.seen()).isTrue();
                    });
        }
    }

    @Test
    void a_mailbox_without_an_smtp_server_says_so_rather_than_failing_at_the_last_step() {
        Map<String, String> readOnly = new LinkedHashMap<>(settings());
        readOnly.remove(MailAccount.SMTP_HOST);
        MailAccounts noSending = new MailAccounts(connections(
                new TestConnection("privat", "Privat", MailAccount.PROVIDER, readOnly)));

        try (MailStore store = noSending.open(ALICE, "email.privat")) {
            assertThat(store.canSend()).isFalse();
            assertThatThrownBy(() -> store.send(new MailDraft(List.of("bob@example.com"),
                    List.of(), "Answer", "…")))
                    .isInstanceOf(MailStoreException.class)
                    .hasMessageContaining("read-only");
        }
    }

    @Test
    void a_mailbox_the_user_does_not_have_is_not_opened() {
        assertThatThrownBy(() -> accounts.open(ALICE, "email.arbeit"))
                .isInstanceOf(MailStoreException.class)
                .hasMessageContaining("not connected");
        assertThatThrownBy(() -> accounts.open(ALICE, "nonsense"))
                .isInstanceOf(MailStoreException.class);
    }

    // ── fixture ─────────────────────────────────────────────────────────────

    // ── organising ──────────────────────────────────────────────────────────

    @Test
    void deleting_puts_a_message_in_the_wastebasket_rather_than_burning_it() throws Exception {
        folder("Trash");
        deliver("Invoice 4711", "due on Friday");
        deliver("Reminder", "still due");

        try (MailStore store = accounts.open(ALICE, "email.privat")) {
            String id = idOf(store, "Invoice 4711");

            List<String> receipt = store.delete("INBOX", List.of(id));

            assertThat(store.list("INBOX", 0, 10, false, null).messages())
                    .extracting(MailMessage::subject).containsExactly("Reminder");
            // In the wastebasket — this used to be flag-and-expunge, gone for good.
            assertThat(store.list("Trash", 0, 10, false, null).messages())
                    .extracting(MailMessage::subject).containsExactly("Invoice 4711");
            assertThat(receipt).hasSize(1);
        }
    }

    @Test
    void undo_brings_a_deleted_message_back_to_where_it_was() throws Exception {
        folder("Trash");
        for (int i = 1; i <= 3; i++) deliver("Message " + i, "body " + i);

        try (MailStore store = accounts.open(ALICE, "email.privat")) {
            List<String> ids = store.list("INBOX", 0, 10, false, null).messages().stream()
                    .map(MailMessage::id).limit(2).toList();

            List<String> receipt = store.delete("INBOX", ids);
            assertThat(store.list("INBOX", 0, 10, false, null).messages()).hasSize(1);

            // The UIDs changed in Trash; the receipt finds them by Message-ID.
            store.restore("INBOX", receipt);

            assertThat(store.list("INBOX", 0, 10, false, null).messages()).hasSize(3);
            assertThat(store.list("Trash", 0, 10, false, null).messages()).isEmpty();
        }
    }

    @Test
    void a_mailbox_without_a_wastebasket_refuses_rather_than_deleting_for_good() throws Exception {
        deliver("Invoice 4711", "due on Friday");

        try (MailStore store = accounts.open(ALICE, "email.privat")) {
            String id = idOf(store, "Invoice 4711");

            assertThatThrownBy(() -> store.delete("INBOX", List.of(id)))
                    .isInstanceOf(MailStoreException.class)
                    .hasMessageContaining("no wastebasket");
            assertThat(store.list("INBOX", 0, 10, false, null).messages()).hasSize(1);
        }
    }

    @Test
    void the_german_wastebasket_is_found_by_its_name() throws Exception {
        folder("Papierkorb");
        deliver("Rechnung", "fällig");

        try (MailStore store = accounts.open(ALICE, "email.privat")) {
            store.delete("INBOX", List.of(idOf(store, "Rechnung")));

            assertThat(store.list("Papierkorb", 0, 10, false, null).messages())
                    .extracting(MailMessage::subject).containsExactly("Rechnung");
        }
    }

    @Test
    void several_at_once_take_one_connection_and_not_one_each() throws Exception {
        folder("Trash");
        for (int i = 1; i <= 4; i++) deliver("Message " + i, "body " + i);

        try (MailStore store = accounts.open(ALICE, "email.privat")) {
            List<String> ids = store.list("INBOX", 0, 10, false, null).messages().stream()
                    .map(MailMessage::id).limit(3).toList();

            store.delete("INBOX", ids);

            assertThat(store.list("INBOX", 0, 10, false, null).messages()).hasSize(1);
        }
    }

    @Test
    void moving_takes_a_message_out_of_one_folder_and_puts_it_in_another() throws Exception {
        deliver("Invoice 4711", "due on Friday");
        MAIL.getManagers().getImapHostManager()
                .createMailbox(MAIL.getManagers().getUserManager().getUser(ADDRESS), "Archiv");

        try (MailStore store = accounts.open(ALICE, "email.privat")) {
            String id = store.list("INBOX", 0, 10, false, null).messages().get(0).id();

            store.move("INBOX", List.of(id), "Archiv");

            assertThat(store.list("INBOX", 0, 10, false, null).messages()).isEmpty();
            assertThat(store.list("Archiv", 0, 10, false, null).messages())
                    .extracting(MailMessage::subject).containsExactly("Invoice 4711");
        }
    }

    @Test
    void moving_nowhere_is_refused_rather_than_guessed_at() throws Exception {
        deliver("Invoice 4711", "due on Friday");

        try (MailStore store = accounts.open(ALICE, "email.privat")) {
            String id = store.list("INBOX", 0, 10, false, null).messages().get(0).id();

            assertThatThrownBy(() -> store.move("INBOX", List.of(id), " "))
                    .isInstanceOf(MailStoreException.class)
                    .hasMessageContaining("which folder");
        }
    }

    @Test
    void nothing_ticked_is_nothing_done_rather_than_an_error() {
        try (MailStore store = accounts.open(ALICE, "email.privat")) {
            store.delete("INBOX", List.of());
            assertThat(store.canOrganise()).isTrue();
        }
    }

    private static String idOf(MailStore store, String subject) {
        return store.list("INBOX", 0, 10, false, null).messages().stream()
                .filter(m -> m.subject().equals(subject))
                .findFirst().orElseThrow().id();
    }

    private static void folder(String name) throws Exception {
        MAIL.getManagers().getImapHostManager()
                .createMailbox(MAIL.getManagers().getUserManager().getUser(ADDRESS), name);
    }

    private void deliver(String subject, String body) {
        deliverFrom("bob@example.com", subject, body);
    }

    /**
     * GreenMail's IMAP SEARCH matches a sender as a whole address where a
     * real server matches a substring of the header, so tests here search for
     * the address and not for part of it. What is being tested is the merge,
     * and that is the same either way.
     */
    private void deliverFrom(String from, String subject, String body) {
        GreenMailUtil.sendTextEmail(ADDRESS, from, subject, body, ServerSetupTest.SMTP);
        assertThat(MAIL.waitForIncomingEmail(5_000, 1)).isTrue();
    }

    private ToolConnection connection() {
        return new TestConnection("privat", "Privat", MailAccount.PROVIDER, settings());
    }

    private static Connections connections(ToolConnection connection) {
        return new Connections() {
            @Override
            public List<ToolConnection> of(UserId userId, String provider) {
                return connection.provider().equals(provider) ? List.of(connection) : List.of();
            }

            @Override
            public Optional<ToolConnection> resolve(UserId userId, String provider, String key) {
                return connection.provider().equals(provider) && connection.key().equals(key)
                        ? Optional.of(connection) : Optional.empty();
            }
        };
    }

    private Map<String, String> settings() {
        Map<String, String> values = new LinkedHashMap<>();
        values.put(MailAccount.HOST, "127.0.0.1");
        values.put(MailAccount.PORT, String.valueOf(ServerSetupTest.IMAP.getPort()));
        values.put(MailAccount.SSL, "false");
        values.put(MailAccount.USER, ADDRESS);
        values.put(MailAccount.PASSWORD, PASSWORD);
        values.put(MailAccount.SMTP_HOST, "127.0.0.1");
        values.put(MailAccount.SMTP_PORT, String.valueOf(ServerSetupTest.SMTP.getPort()));
        values.put(MailAccount.SMTP_SSL, "false");
        values.put(MailAccount.SMTP_STARTTLS, "false");
        values.put(MailAccount.SMTP_USER, ADDRESS);
        values.put(MailAccount.SMTP_PASSWORD, PASSWORD);
        return values;
    }
}
