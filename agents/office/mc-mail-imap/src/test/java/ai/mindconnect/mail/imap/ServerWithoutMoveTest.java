package ai.mindconnect.mail.imap;

import ai.mindconnect.mail.MailMessage;
import ai.mindconnect.mail.MailStore;
import ai.mindconnect.mail.Outcome;
import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.GreenMailUtil;
import com.icegreen.greenmail.util.ServerSetupTest;
import jakarta.mail.Flags;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.Store;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Moving, deleting and undoing against servers that lack MOVE, UIDPLUS or
 * both — GreenMail behind a {@link CapabilityFilter}.
 *
 * <p>Angus sends MOVE only when the server announced it, and asks for new
 * UIDs only with UIDPLUS; it does not fall back on its own. Before the
 * mailbox checked the capabilities itself, every move, every delete (a move
 * to the wastebasket) and every undo failed on such a server.
 */
class ServerWithoutMoveTest {

    @RegisterExtension
    static final GreenMailExtension MAIL = new GreenMailExtension(ServerSetupTest.SMTP_IMAP);

    private static final String ADDRESS = "alice@example.com";
    private static final String PASSWORD = "s3cret";

    private CapabilityFilter filter;

    @BeforeEach
    void setUp() throws Exception {
        MAIL.setUser(ADDRESS, ADDRESS, PASSWORD);
        folder("Archiv");
        folder("Trash");
    }

    @AfterEach
    void tearDown() throws Exception {
        if (filter != null) filter.close();
    }

    @Test
    void without_move_but_with_uidplus_a_message_is_copied_and_expunged_and_its_new_id_is_known() throws Exception {
        deliver("Invoice 4711");
        deliver("Keep me");
        MailStore store = storeWithout("MOVE");
        String id = idOf(store, "INBOX", "Invoice 4711");

        List<Outcome> outcomes = store.move("INBOX", List.of(id), "Archiv");

        assertThat(subjects(store, "INBOX")).containsExactly("Keep me");
        MailMessage moved = store.list("Archiv", 0, 10, false, null).messages().get(0);
        assertThat(moved.subject()).isEqualTo("Invoice 4711");
        assertThat(outcomes).singleElement().isInstanceOfSatisfying(Outcome.Moved.class,
                m -> assertThat(m.newId()).isEqualTo(moved.id()));
    }

    @Test
    void with_move_but_without_uidplus_a_message_moves_and_its_new_id_is_not_guessed() throws Exception {
        deliver("Invoice 4711");
        MailStore store = storeWithout("UIDPLUS");

        List<Outcome> outcomes = store.move("INBOX", List.of(idOf(store, "INBOX", "Invoice 4711")), "Archiv");

        assertThat(subjects(store, "INBOX")).isEmpty();
        assertThat(subjects(store, "Archiv")).containsExactly("Invoice 4711");
        assertThat(outcomes).singleElement().isInstanceOfSatisfying(Outcome.Moved.class,
                m -> assertThat(m.newId()).isNull());
    }

    @Test
    void without_either_the_move_expunges_only_its_own_message() throws Exception {
        deliver("Invoice 4711");
        deliver("Flagged elsewhere");
        // Another client flagged this one deleted and has not expunged yet.
        // A plain EXPUNGE after our copy would take it with ours.
        flagDeleted("Flagged elsewhere");
        MailStore store = storeWithout("MOVE", "UIDPLUS");

        List<Outcome> outcomes = store.move("INBOX", List.of(idOf(store, "INBOX", "Invoice 4711")), "Archiv");

        assertThat(outcomes).singleElement().isInstanceOf(Outcome.Moved.class);
        assertThat(subjects(store, "Archiv")).containsExactly("Invoice 4711");
        assertThat(subjects(store, "INBOX")).containsExactly("Flagged elsewhere");
        assertThat(isFlaggedDeleted("Flagged elsewhere")).isTrue();
    }

    @Test
    void without_either_delete_and_undo_still_work() throws Exception {
        deliver("Message 1");
        deliver("Message 2");
        MailStore store = storeWithout("MOVE", "UIDPLUS");

        List<String> receipt = Outcome.handles(store.delete("INBOX", List.of(idOf(store, "INBOX", "Message 1"))));

        assertThat(subjects(store, "INBOX")).containsExactly("Message 2");
        assertThat(subjects(store, "Trash")).containsExactly("Message 1");

        store.restore("INBOX", receipt);

        assertThat(subjects(store, "INBOX")).containsExactlyInAnyOrder("Message 1", "Message 2");
        assertThat(subjects(store, "Trash")).isEmpty();
    }

    @Test
    void an_id_from_before_the_folder_was_renumbered_names_nothing() throws Exception {
        deliver("Invoice 4711");
        MailStore store = storeWithout();
        String id = idOf(store, "INBOX", "Invoice 4711");
        // The id says under which UIDVALIDITY it was handed out.
        assertThat(id).matches("\\d+-\\d+");
        String[] parts = id.split("-");
        String renumbered = (Long.parseLong(parts[0]) + 1) + "-" + parts[1];

        // The same UID under another UIDVALIDITY is not this message: refused,
        // not read, not moved.
        assertThatThrownBy(() -> store.read("INBOX", renumbered)).hasMessageContaining("UIDVALIDITY");
        assertThat(store.summaries("INBOX", List.of(renumbered))).isEmpty();
        assertThat(store.move("INBOX", List.of(renumbered), "Archiv"))
                .containsExactly(new Outcome.Gone(renumbered));
        assertThat(subjects(store, "INBOX")).containsExactly("Invoice 4711");

        // A bare UID, as ids were written before, still reads.
        assertThat(store.read("INBOX", parts[1]).subject()).isEqualTo("Invoice 4711");
    }

    // ── fixture ─────────────────────────────────────────────────────────────

    private MailStore storeWithout(String... capabilities) throws Exception {
        filter = new CapabilityFilter(ServerSetupTest.IMAP.getPort(), Set.of(capabilities));
        Map<String, String> values = new LinkedHashMap<>();
        values.put(MailAccount.HOST, "127.0.0.1");
        values.put(MailAccount.PORT, String.valueOf(filter.port()));
        values.put(MailAccount.SSL, "false");
        values.put(MailAccount.USER, ADDRESS);
        values.put(MailAccount.PASSWORD, PASSWORD);
        return new ImapMailStore(MailAccount.from(new TestConnection("privat", "Privat", MailAccount.PROVIDER, values)));
    }

    private static List<String> subjects(MailStore store, String folder) {
        return store.list(folder, 0, 10, false, null).messages().stream().map(MailMessage::subject).toList();
    }

    private static String idOf(MailStore store, String folder, String subject) {
        return store.list(folder, 0, 10, false, null).messages().stream()
                .filter(m -> m.subject().equals(subject)).findFirst().orElseThrow().id();
    }

    private static void folder(String name) throws Exception {
        MAIL.getManagers().getImapHostManager()
                .createMailbox(MAIL.getManagers().getUserManager().getUser(ADDRESS), name);
    }

    private static void deliver(String subject) {
        GreenMailUtil.sendTextEmail(ADDRESS, "bob@example.com", subject, "body", ServerSetupTest.SMTP);
        assertThat(MAIL.waitForIncomingEmail(5_000, 1)).isTrue();
    }

    /** Straight at GreenMail, as another mail client would. */
    private static void flagDeleted(String subject) throws Exception {
        try (Store direct = direct()) {
            Folder inbox = direct.getFolder("INBOX");
            inbox.open(Folder.READ_WRITE);
            for (Message message : inbox.getMessages()) {
                if (subject.equals(message.getSubject())) message.setFlag(Flags.Flag.DELETED, true);
            }
            inbox.close(false);
        }
    }

    private static boolean isFlaggedDeleted(String subject) throws Exception {
        try (Store direct = direct()) {
            Folder inbox = direct.getFolder("INBOX");
            inbox.open(Folder.READ_ONLY);
            for (Message message : inbox.getMessages()) {
                if (subject.equals(message.getSubject())) return message.isSet(Flags.Flag.DELETED);
            }
            return false;
        }
    }

    private static Store direct() throws Exception {
        Store store = Session.getInstance(new Properties()).getStore("imap");
        store.connect("127.0.0.1", ServerSetupTest.IMAP.getPort(), ADDRESS, PASSWORD);
        return store;
    }
}
