package ai.mindconnect.office.tools;

import ai.mindconnect.agent.Namespace;
import ai.mindconnect.agent.UserId;
import ai.mindconnect.mail.Location;
import ai.mindconnect.mail.MailAccounts;
import ai.mindconnect.mail.MailMessage;
import ai.mindconnect.mail.index.InMemoryMailIndexStore;
import ai.mindconnect.mail.index.MailIndex;
import ai.mindconnect.mail.view.AgentView;
import ai.mindconnect.mail.view.CurrentView;
import ai.mindconnect.mail.view.FileViewStore;
import ai.mindconnect.mail.view.MailListViews;
import ai.mindconnect.mail.view.StoredView;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the changing tools leave behind in the window index and in the
 * gathered list.
 *
 * <p>The window compares itself with the provider only every two minutes,
 * and then only the newest page. So what a tool changed has to go into the
 * window when the tool changes it, or {@code mail_list} goes on listing a
 * deleted message — for good, when it was older than the newest page.
 */
class MailWriteThroughTest {

    private static final UserId ME = MemoryMail.ME;
    private static final Instant T0 = Instant.parse("2026-09-23T08:00:00Z");

    private final MemoryMail mail = new MemoryMail().box("privat").box("work");
    private final MailAccounts accounts = mail.accounts();
    private final InMemoryMailIndexStore windows = new InMemoryMailIndexStore();
    private final MailIndex index = new MailIndex(windows);
    private final MailTools tools = new MailTools(accounts, java.time.ZoneOffset.UTC, index);

    @Test
    void a_deleted_message_is_not_listed_again() {
        String old = mail.deliver("privat", "INBOX", "Old invoice", "bob@example.com", T0);
        mail.deliver("privat", "INBOX", "Newer", "bob@example.com", T0.plusSeconds(60));
        assertThat(run(MailTools.LIST, Map.of("account", "memory.privat"))).contains("Old invoice");

        String answer = run(MailTools.DELETE, Map.of("account", "memory.privat", "ids", List.of(old)));

        assertThat(answer).startsWith("1 message moved");
        assertThat(run(MailTools.LIST, Map.of("account", "memory.privat"))).doesNotContain("Old invoice")
                .contains("Newer");
    }

    @Test
    void a_moved_message_leaves_one_window_and_arrives_in_the_other_under_its_new_id() {
        String id = mail.deliver("privat", "INBOX", "Invoice 4711", "bob@example.com", T0);
        run(MailTools.LIST, Map.of("account", "memory.privat"));
        run(MailTools.LIST, Map.of("account", "memory.privat", "folder", "Archiv"));

        run(MailTools.MOVE, Map.of("account", "memory.privat", "ids", List.of(id), "to", "Archiv"));

        assertThat(run(MailTools.LIST, Map.of("account", "memory.privat"))).doesNotContain("Invoice 4711");
        String newId = mail.boxes.get("privat").folders.get("Archiv").get(0).id();
        assertThat(index.heads(ME, new Location("memory.privat", "Archiv"), List.of(newId))).containsKey(newId);
        assertThat(run(MailTools.LIST, Map.of("account", "memory.privat", "folder", "Archiv")))
                .contains("- id " + newId + " ").contains("Invoice 4711");
    }

    @Test
    void a_message_marked_read_is_no_longer_listed_as_unread() {
        String id = mail.deliver("privat", "INBOX", "Reminder", "bob@example.com", T0);
        assertThat(run(MailTools.LIST, Map.of("account", "memory.privat", "unread_only", true))).contains("Reminder");

        run(MailTools.MARK_READ, Map.of("account", "memory.privat", "ids", List.of(id)));

        assertThat(run(MailTools.LIST, Map.of("account", "memory.privat", "unread_only", true)))
                .doesNotContain("Reminder");
    }

    @Test
    void taking_one_out_of_the_list_leaves_its_namesake_in_another_mailbox(@TempDir Path dir) {
        // Both mailboxes number from 1: the same id, two different messages.
        String home = mail.deliver("privat", "INBOX", "Home newsletter", "a@example.com", T0);
        String work = mail.deliver("work", "INBOX", "Work newsletter", "b@example.com", T0);
        assertThat(home).isEqualTo(work);
        MailListViews views = MailListViews.standard(accounts,
                new FileViewStore(() -> dir, () -> new Namespace("local"), new ObjectMapper().findAndRegisterModules()),
                index);
        MailListTools lists = new MailListTools(accounts, views, new CurrentView.Memory(), index);
        lists.create(MailListTools.ADD, ME, "chat-1").orElseThrow().execute(Map.of("messages", List.of(
                Map.of("id", home, "account", "memory.privat", "folder", "INBOX"),
                Map.of("id", work, "account", "memory.work", "folder", "INBOX"))));

        // A bare id names both: refused with the two it names, not both taken out.
        String refused = lists.create(MailListTools.REMOVE, ME, "chat-1").orElseThrow()
                .execute(Map.of("ids", List.of(home)));
        assertThat(refused).startsWith("Error:").contains("names 2 messages")
                .contains("account memory.privat").contains("account memory.work");
        assertThat(gathered(views)).hasSize(2);

        String removed = lists.create(MailListTools.REMOVE, ME, "chat-1").orElseThrow()
                .execute(Map.of("messages", List.of(Map.of("id", work, "account", "memory.work", "folder", "INBOX"))));

        assertThat(removed).startsWith("1 taken out, 1 left");
        assertThat(gathered(views)).containsExactly(new MailMessage.Ref(new Location("memory.privat", "INBOX"), home));
    }

    private List<MailMessage.Ref> gathered(MailListViews views) {
        StoredView stored = views.saved(ME, AgentView.KIND).get(0);
        return ((AgentView) views.open(ME, stored.id())).entries();
    }

    private String run(String tool, Map<String, Object> args) {
        String answer = tools.create(tool, ME).orElseThrow().execute(args);
        assertThat(answer).doesNotStartWith("Error:");
        return answer;
    }
}
