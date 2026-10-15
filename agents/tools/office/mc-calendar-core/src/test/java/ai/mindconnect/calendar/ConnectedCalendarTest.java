package ai.mindconnect.calendar;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ConnectedCalendarTest {

    @Test
    void an_id_is_the_provider_and_the_connection_key() {
        assertThat(new ConnectedCalendar("microsoft", "work", "Work", true).id()).isEqualTo("microsoft.work");
        assertThat(ConnectedCalendar.providerOf("microsoft.work")).isEqualTo("microsoft");
        assertThat(ConnectedCalendar.keyOf("microsoft.work")).isEqualTo("work");
    }

    @Test
    void a_key_may_contain_a_dot_and_only_the_first_one_separates() {
        assertThat(ConnectedCalendar.providerOf("google.a.b")).isEqualTo("google");
        assertThat(ConnectedCalendar.keyOf("google.a.b")).isEqualTo("a.b");
    }

    @Test
    void nonsense_is_neither_a_provider_nor_a_key() {
        assertThat(ConnectedCalendar.providerOf("nodot")).isNull();
        assertThat(ConnectedCalendar.keyOf("nodot")).isNull();
        assertThat(ConnectedCalendar.providerOf(".leading")).isNull();
        assertThat(ConnectedCalendar.keyOf("trailing.")).isNull();
    }

    @Test
    void the_label_says_which_account_and_what_it_is() {
        assertThat(new ConnectedCalendar("microsoft", "work", "Work", true).describe())
                .isEqualTo("Work · Outlook");
        assertThat(new ConnectedCalendar("google", "private", null, true).describe())
                .isEqualTo("Google");
    }
}
