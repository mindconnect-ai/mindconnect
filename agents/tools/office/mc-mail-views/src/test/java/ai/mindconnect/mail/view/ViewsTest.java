package ai.mindconnect.mail.view;

import ai.mindconnect.agent.Namespace;
import ai.mindconnect.mail.Location;
import ai.mindconnect.mail.MailMessage;
import ai.mindconnect.mail.Outcome;
import ai.mindconnect.mail.MailStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The three kinds of view against mailboxes in memory, and the eight scenarios of concept 42 where they apply. */
class ViewsTest {

    @TempDir
    Path dir;

    private MemoryMail mail;
    private MailListViews views;

    private static final Instant T = Instant.parse("2026-09-23T08:00:00Z");

    @BeforeEach
    void setUp() {
        mail = new MemoryMail().box("privat").box("arbeit");
        views = MailListViews.standard(mail.accounts(),
                new FileViewStore(() -> dir, () -> new Namespace("local"), new ObjectMapper().findAndRegisterModules()));
    }

    // ── the folder ──────────────────────────────────────────────────────────

    @Test
    void a_folder_is_opened_by_its_name_alone_and_its_search_and_ticks_come_back_with_it() {
        mail.deliver("privat", "INBOX", "Rechnung Swisscom", "bill@swisscom.ch", T.plusSeconds(3));
        mail.deliver("privat", "INBOX", "Newsletter", "news@shop.ch", T.plusSeconds(2));
        mail.deliver("privat", "INBOX", "Mahnung Swisscom", "bill@swisscom.ch", T.plusSeconds(1));
        ViewId inbox = ViewId.folder("memory.privat", "INBOX");

        MailListView fresh = views.open(MemoryMail.ME, inbox);
        assertThat(fresh.kind()).isEqualTo("folder");
        assertThat(fresh.title()).isEqualTo("INBOX");
        assertThat(fresh.lives()).isTrue();
        assertThat(fresh.canRemove()).isFalse();
        assertThat(views.page(fresh, 0, 10).items()).extracting(i -> i.message().subject())
                .containsExactly("Rechnung Swisscom", "Newsletter", "Mahnung Swisscom");

        // Scenario 1: search and tick, page — the search stays. Scenario 3: come back, it is all still there.
        views.save(fresh.withState(fresh.state().search("Swisscom").tick(List.of("memory.privat:1"))));
        MailListView again = views.open(MemoryMail.ME, inbox);
        assertThat(again.title()).isEqualTo("“Swisscom” in INBOX");
        ListPage page = views.page(again, 0, 10);
        assertThat(page.items()).extracting(i -> i.message().subject())
                .containsExactly("Rechnung Swisscom", "Mahnung Swisscom");
        assertThat(page.items().get(0).selected()).isTrue();
        assertThat(page.items().get(1).selected()).isFalse();
        assertThat(page.items().get(0).rowId()).isEqualTo("memory.privat:1");
        assertThat(again.location("memory.privat:1")).isEqualTo(new Location("memory.privat", "INBOX"));
    }

    @Test
    void all_inboxes_is_every_account_merged_newest_first_and_a_row_knows_its_account() {
        mail.deliver("privat", "INBOX", "Privat alt", "a@x.ch", T.plusSeconds(1));
        mail.deliver("arbeit", "INBOX", "Arbeit neu", "b@x.ch", T.plusSeconds(3));
        mail.deliver("privat", "INBOX", "Privat neu", "a@x.ch", T.plusSeconds(2));

        MailListView all = views.open(MemoryMail.ME, ViewId.allInboxes());
        ListPage page = views.page(all, 0, 2);

        assertThat(all.kind()).isEqualTo("all-inboxes");
        assertThat(page.items()).extracting(i -> i.message().subject()).containsExactly("Arbeit neu", "Privat neu");
        assertThat(page.total()).isEqualTo(3);
        assertThat(all.location(page.items().get(0).rowId())).isEqualTo(new Location("memory.arbeit", "INBOX"));
    }

    // ── what an agent gathered ──────────────────────────────────────────────

    @Test
    void an_agents_list_is_gathered_saved_shown_and_read_back_from_wherever_its_rows_lie() {
        String one = mail.deliver("privat", "INBOX", "Werbung 1", "shop@x.ch", T.plusSeconds(1));
        String two = mail.deliver("privat", "Archiv", "Werbung 2", "shop@x.ch", T.plusSeconds(2));
        String three = mail.deliver("arbeit", "INBOX", "Werbung 3", "shop@x.ch", T.plusSeconds(3));

        AgentView gathered = AgentView.empty(MemoryMail.ME, "session-1", ViewId.allInboxes(), mail.accounts())
                .add(List.of(ref("memory.privat", "INBOX", one), ref("memory.privat", "Archiv", two)))
                .add(List.of(ref("memory.arbeit", "INBOX", three), ref("memory.privat", "INBOX", one)))  // twice is once
                .titled("Werbung");
        views.save(gathered);

        // Scenario 5: it is in the store, under its kind, with its title.
        assertThat(views.saved(MemoryMail.ME, "agent")).extracting(StoredView::title).containsExactly("Werbung");
        MailListView back = views.open(MemoryMail.ME, gathered.id());
        assertThat(back).isInstanceOf(AgentView.class);
        assertThat(back.title()).isEqualTo("Werbung");
        assertThat(back.lives()).isFalse();
        assertThat(back.canRemove()).isTrue();
        assertThat(((AgentView) back).from()).isEqualTo(ViewId.allInboxes());

        // Rows come from three folders of two accounts, in the order gathered.
        ListPage page = views.page(back, 0, 10);
        assertThat(page.items()).extracting(i -> i.message().subject())
                .containsExactly("Werbung 1", "Werbung 2", "Werbung 3");
        assertThat(page.total()).isEqualTo(3);
        assertThat(back.location("memory.privat:" + two)).isEqualTo(new Location("memory.privat", "Archiv"));
    }

    @Test
    void scenario_6_moving_out_of_the_list_keeps_the_row_under_its_new_place_and_id() {
        String one = mail.deliver("privat", "INBOX", "Werbung 1", "shop@x.ch", T.plusSeconds(1));
        String two = mail.deliver("privat", "INBOX", "Werbung 2", "shop@x.ch", T.plusSeconds(2));
        AgentView gathered = AgentView.empty(MemoryMail.ME, "s", null, mail.accounts())
                .add(List.of(ref("memory.privat", "INBOX", one), ref("memory.privat", "INBOX", two)));
        gathered = (AgentView) gathered.withState(gathered.state().tick(List.of("memory.privat:" + one)));

        // What the screen does: move through the store, then tell the view.
        List<Outcome> outcomes;
        try (MailStore store = mail.accounts().open(MemoryMail.ME, "memory.privat")) {
            outcomes = store.move("INBOX", List.of(one), "Archiv");
        }
        Outcome.Moved moved = (Outcome.Moved) outcomes.get(0);
        MailListView after = gathered.afterMove(Map.of("memory.privat:" + one,
                new MailMessage.Ref(moved.to(), moved.newId())));

        ListPage page = views.page(after, 0, 10);
        assertThat(page.items()).extracting(i -> i.message().subject()).containsExactly("Werbung 1", "Werbung 2");
        assertThat(page.items().get(0).location()).isEqualTo(new Location("memory.privat", "Archiv"));
        // The id changed on the way, and the tick followed it.
        assertThat(page.items().get(0).rowId()).isEqualTo("memory.privat:" + moved.newId());
        assertThat(page.items().get(0).selected()).isTrue();
        // A folder, by contrast, loses a moved row.
        MailListView inbox = views.open(MemoryMail.ME, ViewId.folder("memory.privat", "INBOX"));
        assertThat(views.page(inbox, 0, 10).items()).extracting(i -> i.message().subject()).containsExactly("Werbung 2");
    }

    @Test
    void scenario_7_and_8_what_is_deleted_or_gone_falls_out_of_the_list_and_the_page_says_so() {
        String one = mail.deliver("privat", "INBOX", "Werbung 1", "shop@x.ch", T.plusSeconds(1));
        String two = mail.deliver("privat", "INBOX", "Werbung 2", "shop@x.ch", T.plusSeconds(2));
        String three = mail.deliver("privat", "INBOX", "Werbung 3", "shop@x.ch", T.plusSeconds(3));
        AgentView gathered = AgentView.empty(MemoryMail.ME, "s", null, mail.accounts())
                .add(List.of(ref("memory.privat", "INBOX", one), ref("memory.privat", "INBOX", two),
                        ref("memory.privat", "INBOX", three)));
        views.save(gathered);

        // 7: deleted from the list — the screen deletes, then takes the row out.
        views.save(gathered.without(List.of("memory.privat:" + one)));
        // 8: deleted in another client — nobody told the list.
        mail.vanish("privat", "INBOX", two);

        MailListView back = views.open(MemoryMail.ME, gathered.id());
        ListPage page = views.page(back, 0, 10);
        assertThat(page.items()).extracting(i -> i.message().subject()).containsExactly("Werbung 3");
        assertThat(page.gone()).containsExactly("memory.privat:" + two);
        assertThat(page.total()).isEqualTo(1);
        // Taken out quietly and saved away: the next read does not look for it again.
        assertThat(views.page(views.open(MemoryMail.ME, gathered.id()), 0, 10).gone()).isEmpty();
        assertThat(((AgentView) views.open(MemoryMail.ME, gathered.id())).size()).isEqualTo(1);
    }

    @Test
    void a_name_nobody_knows_is_refused_and_a_list_holds_no_more_than_fits_on_a_screen() {
        assertThatThrownBy(() -> views.open(MemoryMail.ME, ViewId.of("s/nothing")))
                .isInstanceOf(NoSuchViewException.class);
        assertThatThrownBy(() -> views.open(MemoryMail.ME, ViewId.of("x/what")))
                .isInstanceOf(NoSuchViewException.class);

        AgentView big = AgentView.empty(MemoryMail.ME, "s", null, mail.accounts());
        List<MailMessage.Ref> many = new java.util.ArrayList<>();
        for (int i = 0; i < AgentView.MAX + 20; i++) many.add(ref("memory.privat", "INBOX", String.valueOf(i)));
        assertThat(big.add(many).size()).isEqualTo(AgentView.MAX);
        assertThat(big.add(many).full()).isTrue();
    }

    private static MailMessage.Ref ref(String account, String folder, String id) {
        return new MailMessage.Ref(new Location(account, folder), id);
    }
}
