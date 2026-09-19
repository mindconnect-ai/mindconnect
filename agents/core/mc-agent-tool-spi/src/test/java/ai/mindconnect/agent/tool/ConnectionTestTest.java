package ai.mindconnect.agent.tool;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** A verdict, and — when the source can tell — whose account it was. */
class ConnectionTestTest {

    @Test
    void a_pass_may_name_the_account() {
        ConnectionTest test = ConnectionTest.ok("Signed in to Microsoft as David <david@example.com>.", "david@example.com");

        assertThat(test.ok()).isTrue();
        assertThat(test.namedAccount()).contains("david@example.com");
    }

    @Test
    void a_source_that_cannot_tell_names_nobody() {
        assertThat(ConnectionTest.ok("Signed in to imap.example.com.").namedAccount()).isEmpty();
        assertThat(ConnectionTest.failed("wrong password").namedAccount()).isEmpty();
        assertThat(new ConnectionTest(true, "fine").namedAccount()).isEmpty();
        assertThat(ConnectionTest.ok("fine", "  ").namedAccount()).isEmpty();
    }

    @Test
    void the_account_is_trimmed() {
        assertThat(ConnectionTest.ok("fine", " david@example.com ").account()).isEqualTo("david@example.com");
    }
}
