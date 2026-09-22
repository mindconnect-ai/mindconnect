package ai.mindconnect.office.tools;

import ai.mindconnect.mail.ConnectedMailbox;
import ai.mindconnect.mail.MailFolder;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The mail tools' own logic: which mailbox a call means, and what its arguments say. */
class MailToolsTest {

    private static final ConnectedMailbox WORK = new ConnectedMailbox("microsoft", "work", "Work", true);
    private static final ConnectedMailbox HOME = new ConnectedMailbox("email", "home", "Home", true);
    private static final ConnectedMailbox OLD = new ConnectedMailbox("google", "old", "Old", false);

    private static Accounts<ConnectedMailbox> accounts(ConnectedMailbox... boxes) {
        return new Accounts<>(List.of(boxes), ConnectedMailbox::id, ConnectedMailbox::key,
                ConnectedMailbox::describe, ConnectedMailbox::usable);
    }

    @Test
    void an_account_is_named_by_id_or_by_an_unambiguous_key() {
        var two = accounts(WORK, HOME);

        assertThat(two.one(Map.of("account", "microsoft.work"))).isEqualTo(WORK);
        assertThat(two.one(Map.of("account", "home"))).isEqualTo(HOME);
        assertThatThrownBy(() -> two.one(Map.of("account", "nope")))
                .hasMessageContaining("microsoft.work").hasMessageContaining("email.home");
    }

    @Test
    void no_account_means_the_only_one_and_is_refused_among_several_where_all_is_not_offered() {
        assertThat(accounts(HOME).one(Map.of())).isEqualTo(HOME);
        assertThatThrownBy(() -> accounts(WORK, HOME).one(Map.of())).hasMessageContaining("Several accounts");
        // A listing takes every one.
        assertThat(accounts(WORK, HOME).pick(Map.of(), true)).containsExactly(WORK, HOME);
        assertThat(accounts(WORK, HOME).pick(Map.of("account", "all"), true)).containsExactly(WORK, HOME);
        assertThatThrownBy(() -> accounts(WORK, HOME).pick(Map.of("account", "all"), false))
                .hasMessageContaining("one account");
    }

    @Test
    void an_account_that_needs_connecting_again_is_not_offered() {
        var three = accounts(WORK, HOME, OLD);

        assertThat(three.choices(true)).containsExactly("all", "microsoft.work", "email.home");
        assertThat(three.pick(Map.of("account", "all"), true)).doesNotContain(OLD);
        assertThat(three.describeAll()).contains("google.old").contains("needs to be connected again");
        assertThatThrownBy(() -> accounts(OLD).one(Map.of())).hasMessageContaining("No account");
    }

    @Test
    void dates_are_read_as_a_person_writes_them() {
        ZoneId zurich = ZoneId.of("Europe/Zurich");

        assertThat(OfficeTool.instant(Map.of("since", "2026-09-22"), "since", zurich))
                .isEqualTo(Instant.parse("2026-09-21T22:00:00Z"));
        assertThat(OfficeTool.instant(Map.of("at", "2026-09-22T14:00"), "at", zurich))
                .isEqualTo(Instant.parse("2026-09-22T12:00:00Z"));
        assertThat(OfficeTool.instant(Map.of("at", "2026-09-22T14:00:00Z"), "at", zurich))
                .isEqualTo(Instant.parse("2026-09-22T14:00:00Z"));
        assertThatThrownBy(() -> OfficeTool.instant(Map.of("at", "next Tuesday"), "at", zurich))
                .hasMessageContaining("2026-09-22");
    }

    @Test
    void lists_come_as_arrays_or_as_one_line() {
        assertThat(OfficeTool.list(Map.of("to", List.of("a@x.ch", " b@x.ch ")), "to")).containsExactly("a@x.ch", "b@x.ch");
        assertThat(OfficeTool.list(Map.of("to", "a@x.ch, b@x.ch"), "to")).containsExactly("a@x.ch", "b@x.ch");
        assertThat(OfficeTool.list(Map.of(), "to")).isEmpty();
    }

    @Test
    void a_folder_is_named_by_id_or_name_and_the_inbox_is_the_default() {
        List<MailFolder> folders = List.of(MailFolder.of("AAMk=", "Inbox"), MailFolder.of("AAMz=", "Archive"));

        assertThat(MailTools.folder(folders, null)).isEqualTo("AAMk=");
        List<MailFolder> german = List.of(MailFolder.of("P=", "Posteingang"), MailFolder.of("A=", "Archiv"));
        assertThat(MailTools.folder(german, "inbox")).as("the store lists the inbox first").isEqualTo("P=");
        assertThat(MailTools.folder(folders, "archive")).isEqualTo("AAMz=");
        assertThat(MailTools.folder(folders, "AAMz=")).isEqualTo("AAMz=");
    }

    @Test
    void a_folder_that_is_not_there_is_refused_with_the_ones_that_are() {
        List<MailFolder> folders = List.of(MailFolder.of("AAMk=", "Inbox"), MailFolder.of("A=", "Archiv"),
                MailFolder.of("Papierkorb", "Papierkorb"));

        // Sending the caller to mail_folders for a list this very call is
        // holding costs a round trip to learn what fits in the sentence.
        assertThatThrownBy(() -> MailTools.folder(folders, "Spam"))
                .hasMessageContaining("There is no folder \"Spam\"")
                .hasMessageContaining("AAMk= (\"Inbox\")")
                .hasMessageContaining("A= (\"Archiv\")")
                // Id and name are the same word: saying it twice helps nobody.
                .hasMessageContaining(", Papierkorb.")
                .hasMessageNotContaining("mail_folders");

        // A mailbox with hundreds of folders is not a listing in a refusal.
        List<MailFolder> many = new java.util.ArrayList<>();
        for (int i = 0; i < MailTools.MAX_FOLDERS_NAMED + 5; i++) many.add(MailFolder.of("f" + i, "f" + i));
        assertThatThrownBy(() -> MailTools.folder(many, "Spam"))
                .hasMessageContaining("and 5 more").hasMessageContaining("mail_folders lists them all");

        assertThatThrownBy(() -> MailTools.folder(List.of(), "Spam"))
                .hasMessageContaining("This mailbox names none");
    }

    @Test
    void a_failure_comes_back_as_a_sentence() {
        OfficeTool refusing = new OfficeTool("x", "x", Map.of(), args -> { throw new OfficeTool.Refused("No such folder."); });
        OfficeTool breaking = new OfficeTool("x", "x", Map.of(), args -> { throw new IllegalStateException("boom"); });

        assertThat(refusing.execute(Map.of())).isEqualTo("Error: No such folder.");
        assertThat(breaking.execute(null)).isEqualTo("Error: x failed: boom");
    }

    @Test
    void the_mail_tools_are_one_family_and_every_change_a_tool_of_its_own() {
        MailToolProvider provider = new MailToolProvider();

        assertThat(provider.group()).isEqualTo("office");
        assertThat(provider.subgroup("mail_list")).isEqualTo("Mail");
        assertThat(provider.toolNames()).containsExactlyInAnyOrder("mail_folders", "mail_list", "mail_read",
                        "mail_mark_read", "mail_move", "mail_delete", "mail_send")
                // No provider in a name any more: one set for every kind of mailbox.
                .noneMatch(n -> n.startsWith("outlook_") || n.startsWith("gmail_") || n.startsWith("imap_"));
        // Nothing bound: nothing offered.
        assertThat(provider.isAvailable()).isFalse();
        assertThat(provider.create("mail_list", null, null)).isEmpty();
    }
}
