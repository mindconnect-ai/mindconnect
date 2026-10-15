package ai.mindconnect.mail;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ConnectedMailboxTest {

    @Test
    void an_id_survives_the_round_trip_through_a_url() {
        ConnectedMailbox mailbox = new ConnectedMailbox("microsoft", "arbeit-2", "Arbeit", true);

        assertThat(mailbox.id()).isEqualTo("microsoft.arbeit-2");
        assertThat(ConnectedMailbox.providerOf(mailbox.id())).isEqualTo("microsoft");
        assertThat(ConnectedMailbox.keyOf(mailbox.id())).isEqualTo("arbeit-2");
    }

    @Test
    void something_that_is_not_an_id_answers_with_nothing_rather_than_half_of_one() {
        assertThat(ConnectedMailbox.providerOf("nonsense")).isNull();
        assertThat(ConnectedMailbox.keyOf(null)).isNull();
    }

    @Test
    void the_switcher_says_which_kind_of_account_it_is() {
        assertThat(new ConnectedMailbox("google", "privat", "Privat", true).describe())
                .isEqualTo("Privat · Gmail");
        assertThat(new ConnectedMailbox("email", "uni", "Uni", true).describe())
                .isEqualTo("Uni · IMAP");
    }

    @Test
    void a_connection_without_a_label_is_known_by_its_key() {
        assertThat(new ConnectedMailbox("email", "uni", "  ", true).label()).isEqualTo("uni");
    }
}
