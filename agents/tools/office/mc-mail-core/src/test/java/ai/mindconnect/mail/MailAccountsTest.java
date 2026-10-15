package ai.mindconnect.mail;

import ai.mindconnect.agent.UserId;
import ai.mindconnect.agent.tool.Connections;
import ai.mindconnect.agent.tool.ToolConnection;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The seam: which kinds of mailbox exist is what the classpath brought, and
 * a user's mailboxes are the connections of those kinds.
 */
class MailAccountsTest {

    private static final UserId ME = UserId.of("me");

    @Test
    void a_user_has_the_mailboxes_of_every_kind_the_installation_can_open() {
        MailAccounts accounts = new MailAccounts(
                connections(Map.of("email", List.of(connection("email", "work", "Work", true)),
                        "microsoft", List.of(connection("microsoft", "office", "Office", true),
                                connection("microsoft", "old", "Old", false)))),
                List.of(opening("email"), opening("microsoft")));

        assertThat(accounts.providers()).containsExactly("email", "microsoft");
        assertThat(accounts.of(ME)).extracting(ConnectedMailbox::id)
                .containsExactly("email.work", "microsoft.office", "microsoft.old");
        assertThat(accounts.of(ME)).extracting(ConnectedMailbox::usable).containsExactly(true, true, false);
        assertThat(accounts.find(ME, "microsoft.office")).map(ConnectedMailbox::label).contains("Office");
        assertThat(accounts.find(ME, "google.private")).isEmpty();
    }

    @Test
    void without_a_kind_on_the_classpath_its_mailboxes_are_not_there() {
        // The same installation with only the IMAP module: Outlook connections
        // exist in the store, but nothing can open them, so nothing offers them.
        MailAccounts imapOnly = new MailAccounts(
                connections(Map.of("email", List.of(connection("email", "work", "Work", true)),
                        "microsoft", List.of(connection("microsoft", "office", "Office", true)))),
                List.of(opening("email")));

        assertThat(imapOnly.of(ME)).extracting(ConnectedMailbox::id).containsExactly("email.work");
        // The IMAP provider is asked and answers (this one with nothing at all).
        assertThatCode(() -> imapOnly.open(ME, "email.work")).doesNotThrowAnyException();
        assertThatThrownBy(() -> imapOnly.open(ME, "microsoft.office"))
                .isInstanceOf(MailStoreException.class)
                .hasMessageContaining("microsoft");
    }

    @Test
    void an_id_that_is_not_a_mailbox_of_theirs_is_refused_before_anything_is_opened() {
        MailAccounts accounts = new MailAccounts(connections(Map.of()), List.of(opening("email")));

        assertThatThrownBy(() -> accounts.open(ME, "nonsense"))
                .isInstanceOf(MailStoreException.class).hasMessageContaining("not a mailbox");
        assertThatThrownBy(() -> accounts.open(ME, "email.gone"))
                .isInstanceOf(MailStoreException.class).hasMessageContaining("not connected any more");
    }

    // ── the fixtures ────────────────────────────────────────────────────────

    private static MailProvider opening(String provider) {
        return new MailProvider() {
            @Override public String provider() {
                return provider;
            }

            @Override public MailStore open(ToolConnection connection) {
                return null;   // nothing here opens a real mailbox
            }
        };
    }

    private static Connections connections(Map<String, List<ToolConnection>> byProvider) {
        return new Connections() {
            @Override public List<ToolConnection> of(UserId userId, String provider) {
                return byProvider.getOrDefault(provider, List.of());
            }

            @Override public Optional<ToolConnection> resolve(UserId userId, String provider, String key) {
                return of(userId, provider).stream().filter(c -> c.key().equals(key)).findFirst();
            }
        };
    }

    private static ToolConnection connection(String provider, String key, String label, boolean usable) {
        return new ToolConnection() {
            @Override public String key() {
                return key;
            }

            @Override public String label() {
                return label;
            }

            @Override public String provider() {
                return provider;
            }

            @Override public String value(String field) {
                return null;
            }

            @Override public boolean usable() {
                return usable;
            }
        };
    }
}
