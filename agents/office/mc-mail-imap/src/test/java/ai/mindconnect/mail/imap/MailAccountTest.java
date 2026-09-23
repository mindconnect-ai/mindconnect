package ai.mindconnect.mail.imap;

import ai.mindconnect.agent.tool.ConnectionSpec;
import ai.mindconnect.schema.Schema;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MailAccountTest {

    @Test
    void the_three_values_nobody_can_guess_are_the_required_ones() {
        Schema schema = MailAccount.schema();

        assertThat(schema.getProperties()).containsKeys(MailAccount.HOST, MailAccount.USER,
                MailAccount.PASSWORD, MailAccount.PROTOCOL, MailAccount.SMTP_HOST);
        assertThat(MailAccount.HOST).satisfies(name -> assertThat(schema.isRequired(name)).isTrue());
        assertThat(MailAccount.USER).satisfies(name -> assertThat(schema.isRequired(name)).isTrue());
        assertThat(MailAccount.PASSWORD).satisfies(name -> assertThat(schema.isRequired(name)).isTrue());
    }

    @Test
    void sending_is_never_required_because_most_people_only_want_to_read() {
        Schema schema = MailAccount.schema();

        assertThat(schema.getProperties().keySet()).filteredOn(name -> name.startsWith("smtp"))
                .allSatisfy(name -> assertThat(schema.isRequired(name)).isFalse());
    }

    @Test
    void only_the_passwords_are_secret() {
        Schema schema = MailAccount.schema();

        assertThat(schema.getProperties()).allSatisfy((name, property) -> {
            boolean secret = property.getFormat() == Schema.Format.PASSWORD;
            assertThat(secret)
                    .as("%s secret?", name)
                    .isEqualTo(name.equals(MailAccount.PASSWORD) || name.equals(MailAccount.SMTP_PASSWORD));
        });
    }

    @Test
    void the_card_says_several_mailboxes_are_allowed() {
        ConnectionSpec spec = MailAccount.connectionSpec();

        assertThat(spec.provider()).isEqualTo("email");
        assertThat(spec.multiple()).isTrue();
        assertThat(spec.form()).isPresent();
        assertThat(spec.params()).singleElement()
                .satisfies(param -> assertThat(param.name()).isEqualTo("account"));
    }

    @Test
    void the_defaults_carry_a_working_imaps_mailbox() {
        MailAccount account = account(Map.of());

        assertThat(account.protocol()).isEqualTo("imap");
        assertThat(account.port()).isEqualTo(993);
        assertThat(account.ssl()).isTrue();
        assertThat(account.folder()).isEqualTo("INBOX");
        assertThat(account.storeProtocol()).isEqualTo("imaps");
        assertThat(account.canSend()).isFalse();
    }

    @Test
    void a_missing_value_names_the_mailbox_and_where_to_fix_it() {
        assertThatThrownBy(() -> MailAccount.from(connection(Map.of()).without(MailAccount.PASSWORD)))
                .isInstanceOf(MailConfigurationException.class)
                .hasMessageContaining("Privat")
                .hasMessageContaining("its password")
                .hasMessageContaining("Connections");
    }

    @Test
    void the_default_port_follows_the_protocol_and_the_tls_setting() {
        assertThat(account(Map.of(MailAccount.PROTOCOL, "pop3")).port()).isEqualTo(995);
        assertThat(account(Map.of(MailAccount.PROTOCOL, "pop3", MailAccount.SSL, "false")).port()).isEqualTo(110);
        assertThat(account(Map.of(MailAccount.SSL, "false")).port()).isEqualTo(143);
        assertThat(account(Map.of(MailAccount.PORT, "1143")).port()).isEqualTo(1143);
    }

    @Test
    void a_protocol_nobody_speaks_is_refused_with_the_two_that_work() {
        assertThatThrownBy(() -> account(Map.of(MailAccount.PROTOCOL, "exchange")))
                .isInstanceOf(MailConfigurationException.class)
                .hasMessageContaining("imap or pop3");
    }

    @Test
    void a_port_that_is_not_a_number_says_so_rather_than_defaulting_quietly() {
        assertThatThrownBy(() -> account(Map.of(MailAccount.PORT, "nine-nine-three")))
                .isInstanceOf(MailConfigurationException.class)
                .hasMessageContaining("Privat");
    }

    @Test
    void sending_falls_back_to_the_reading_account_when_it_is_the_same_one() {
        MailAccount account = account(Map.of(MailAccount.SMTP_HOST, "smtp.example.com"));

        assertThat(account.canSend()).isTrue();
        assertThat(account.smtpPort()).isEqualTo(587);
        assertThat(account.smtpUser()).isEqualTo("alice@example.com");
        assertThat(account.smtpPassword()).isEqualTo("s3cret");
        assertThat(account.from()).isEqualTo("alice@example.com");
    }

    @Test
    void a_separate_sender_address_wins_over_the_account() {
        MailAccount account = account(Map.of(
                MailAccount.SMTP_HOST, "smtp.example.com",
                MailAccount.SMTP_USER, "relay",
                MailAccount.FROM, "team@example.com"));

        assertThat(account.smtpUser()).isEqualTo("relay");
        assertThat(account.from()).isEqualTo("team@example.com");
    }

    @Test
    void a_plain_port_still_offers_to_upgrade_rather_than_sending_the_password_in_the_clear() {
        assertThat(account(Map.of(MailAccount.SSL, "false")).storeProperties())
                .containsEntry("mail.imap.starttls.enable", "true");
    }

    @Test
    void the_password_never_appears_in_the_string_form() {
        assertThat(account(Map.of()).toString()).doesNotContain("s3cret").contains("alice@example.com");
    }

    // ── fixtures ────────────────────────────────────────────────────────────

    private static MailAccount account(Map<String, String> overrides) {
        return MailAccount.from(connection(overrides));
    }

    private static FakeConnection connection(Map<String, String> overrides) {
        Map<String, String> values = new LinkedHashMap<>(Map.of(
                MailAccount.HOST, "imap.example.com",
                MailAccount.USER, "alice@example.com",
                MailAccount.PASSWORD, "s3cret"));
        values.putAll(overrides);
        return FakeConnection.of("privat", values);
    }
}
