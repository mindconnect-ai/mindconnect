package ai.mindconnect.mail.imap;

import ai.mindconnect.agent.tool.ConnectionTest;
import ai.mindconnect.agent.tool.ConnectionTester;
import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.ServerSetupTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The Test button, against a real IMAP and SMTP server. */
@DisplayName("Testing a mailbox connection")
class ConnectionProbeTest {

    @RegisterExtension
    static final GreenMailExtension MAIL = new GreenMailExtension(ServerSetupTest.SMTP_IMAP);

    private final ConnectionTester tester = new MailboxConnectionProvider().connectionTester().orElseThrow();

    @BeforeEach
    void user() {
        MAIL.setUser("alice@example.com", "alice@example.com", "s3cret");
    }

    private Map<String, String> settings(String password, boolean withSmtp) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put(MailAccount.HOST, "127.0.0.1");
        values.put(MailAccount.PORT, String.valueOf(MAIL.getImap().getPort()));
        values.put(MailAccount.SSL, "false");
        values.put(MailAccount.USER, "alice@example.com");
        values.put(MailAccount.PASSWORD, password);
        if (withSmtp) {
            values.put(MailAccount.SMTP_HOST, "127.0.0.1");
            values.put(MailAccount.SMTP_PORT, String.valueOf(MAIL.getSmtp().getPort()));
            values.put(MailAccount.SMTP_STARTTLS, "false");
            values.put(MailAccount.SMTP_SSL, "false");
        }
        return values;
    }

    @Test
    @DisplayName("signs in to both halves and says what it found")
    void bothHalves() {
        ConnectionTest result = tester.test(FakeConnection.of("privat", settings("s3cret", true)));

        assertThat(result.ok()).isTrue();
        assertThat(result.message()).contains("Signed in to 127.0.0.1 as alice@example.com")
                .contains("INBOX holds 0 messages").contains("accepted the sign-in for sending");
    }

    @Test
    @DisplayName("says a mailbox without SMTP is read-only rather than failing it")
    void readOnly() {
        ConnectionTest result = tester.test(FakeConnection.of("privat", settings("s3cret", false)));

        assertThat(result.ok()).isTrue();
        assertThat(result.message()).contains("read-only");
    }

    @Test
    @DisplayName("fails with the server's reason on a wrong password, and never shows it")
    void wrongPassword() {
        ConnectionTest result = tester.test(FakeConnection.of("privat", settings("nope", true)));

        assertThat(result.ok()).isFalse();
        assertThat(result.message()).doesNotContain("nope").doesNotContain("s3cret");
    }

    @Test
    @DisplayName("fails with the missing field when the connection is incomplete")
    void incomplete() {
        ConnectionTest result = tester.test(FakeConnection.of("privat", settings("s3cret", true)).without(MailAccount.HOST));

        assertThat(result.ok()).isFalse();
        assertThat(result.message()).contains("Connections");
    }
}
