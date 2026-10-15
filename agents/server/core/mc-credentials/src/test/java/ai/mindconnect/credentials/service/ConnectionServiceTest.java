package ai.mindconnect.credentials.service;

import ai.mindconnect.agent.UserId;
import ai.mindconnect.credentials.adapter.memory.InMemoryConnectionRepository;
import ai.mindconnect.credentials.domain.Connection;
import ai.mindconnect.credentials.domain.ConnectionState;
import ai.mindconnect.credentials.domain.FormCreds;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ConnectionServiceTest {

    private static final UserId ALICE = UserId.of("alice");
    private static final UserId BOB = UserId.of("bob");
    private static final String EMAIL = "email";
    private static final Set<String> SECRET = Set.of("password");

    private final ConnectionService service = new ConnectionService(new InMemoryConnectionRepository());

    @Test
    void the_schema_decides_which_half_a_field_lands_in() {
        Connection added = service.add(ALICE, EMAIL, "Arbeit",
                values("host", "imap.example.com", "password", "s3cret"), SECRET);

        assertThat(added.settings()).containsExactly(Map.entry("host", "imap.example.com"));
        assertThat(added.credentials()).isInstanceOf(FormCreds.class);
        assertThat(((FormCreds) added.credentials()).get("password")).isEqualTo("s3cret");
        // and a tool reads across both without knowing the difference
        assertThat(added.value("host")).isEqualTo("imap.example.com");
        assertThat(added.value("password")).isEqualTo("s3cret");
    }

    @Test
    void the_first_connection_of_a_provider_becomes_the_default() {
        Connection first = service.add(ALICE, EMAIL, "Privat", values("host", "a"), SECRET);
        Connection second = service.add(ALICE, EMAIL, "Arbeit", values("host", "b"), SECRET);

        assertThat(first.isDefault()).isTrue();
        assertThat(second.isDefault()).isFalse();
        assertThat(service.resolve(ALICE, EMAIL, null)).contains(first);
    }

    @Test
    void making_one_the_default_takes_it_off_the_other() {
        service.add(ALICE, EMAIL, "Privat", values("host", "a"), SECRET);
        Connection arbeit = service.add(ALICE, EMAIL, "Arbeit", values("host", "b"), SECRET);

        assertThat(service.setDefault(ALICE, arbeit.id())).isTrue();

        assertThat(service.of(ALICE, EMAIL)).filteredOn(Connection::isDefault)
                .extracting(Connection::key).containsExactly("arbeit");
    }

    @Test
    void removing_the_default_promotes_the_next_one() {
        Connection privat = service.add(ALICE, EMAIL, "Privat", values("host", "a"), SECRET);
        service.add(ALICE, EMAIL, "Arbeit", values("host", "b"), SECRET);

        assertThat(service.remove(ALICE, privat.id())).isTrue();

        assertThat(service.of(ALICE, EMAIL)).singleElement()
                .satisfies(left -> {
                    assertThat(left.key()).isEqualTo("arbeit");
                    assertThat(left.isDefault()).isTrue();
                });
    }

    @Test
    void a_second_connection_of_the_same_name_gets_its_own_key() {
        // A key is what a tool call and a pinned parameter name: the first
        // "arbeit" must keep meaning the first mailbox.
        Connection first = service.add(ALICE, EMAIL, "Arbeit", values("host", "a"), SECRET);
        Connection second = service.add(ALICE, EMAIL, "Arbeit", values("host", "b"), SECRET);

        assertThat(first.key()).isEqualTo("arbeit");
        assertThat(second.key()).isEqualTo("arbeit-2");
        assertThat(service.resolve(ALICE, EMAIL, "arbeit")).contains(first);
    }

    @Test
    void a_label_that_reduces_to_nothing_still_gets_a_key() {
        assertThat(service.add(ALICE, EMAIL, "📮", values("host", "a"), SECRET).key()).isEqualTo("default");
    }

    @Test
    void a_blank_secret_on_edit_keeps_the_stored_one() {
        // The form never shows a password back, so it comes in empty on every
        // rename. Taking that literally would wipe the credential.
        Connection added = service.add(ALICE, EMAIL, "Arbeit",
                values("host", "imap.example.com", "password", "s3cret"), SECRET);

        Connection updated = service.update(ALICE, added.id(), "Arbeit",
                values("host", "imap2.example.com", "password", ""), SECRET).orElseThrow();

        assertThat(updated.value("host")).isEqualTo("imap2.example.com");
        assertThat(updated.value("password")).isEqualTo("s3cret");
    }

    @Test
    void a_new_secret_replaces_the_stored_one() {
        Connection added = service.add(ALICE, EMAIL, "Arbeit", values("password", "old"), SECRET);

        Connection updated = service.update(ALICE, added.id(), null, values("password", "new"), SECRET)
                .orElseThrow();

        assertThat(updated.value("password")).isEqualTo("new");
    }

    @Test
    void renaming_leaves_the_key_alone() {
        Connection added = service.add(ALICE, EMAIL, "Arbeit", values("host", "a"), SECRET);

        Connection renamed = service.update(ALICE, added.id(), "Büro", values("host", "a"), SECRET).orElseThrow();

        assertThat(renamed.label()).isEqualTo("Büro");
        assertThat(renamed.key()).isEqualTo("arbeit");
    }

    @Test
    void nobody_reaches_another_users_connection() {
        Connection alices = service.add(ALICE, EMAIL, "Arbeit", values("host", "a"), SECRET);

        assertThat(service.find(BOB, alices.id())).isEmpty();
        assertThat(service.remove(BOB, alices.id())).isFalse();
        assertThat(service.setDefault(BOB, alices.id())).isFalse();
        assertThat(service.of(BOB, EMAIL)).isEmpty();
    }

    @Test
    void an_unknown_key_resolves_to_nothing_rather_than_to_the_default() {
        // Silently using the private mailbox when "arbeit" was asked for is
        // the one failure mode worth being strict about.
        service.add(ALICE, EMAIL, "Privat", values("host", "a"), SECRET);

        assertThat(service.resolve(ALICE, EMAIL, "arbeit")).isEmpty();
    }

    @Test
    void a_connection_that_was_refused_says_so_until_it_is_put_right() {
        Connection added = service.add(ALICE, EMAIL, "Arbeit", values("host", "a"), SECRET);

        service.markUnusable(added.id(), ConnectionState.ERROR, "the server refused the password");
        assertThat(service.resolve(ALICE, EMAIL, "arbeit")).get()
                .satisfies(c -> {
                    assertThat(c.usable()).isFalse();
                    assertThat(c.stateDetail()).contains("refused");
                });

        service.markUsable(added.id());
        assertThat(service.resolve(ALICE, EMAIL, "arbeit")).get()
                .satisfies(c -> assertThat(c.usable()).isTrue());
    }

    @Test
    void providers_do_not_see_each_others_connections() {
        service.add(ALICE, EMAIL, "Arbeit", values("host", "a"), SECRET);
        service.add(ALICE, "calendar", "Arbeit", values("url", "b"), Set.of());

        assertThat(service.of(ALICE, EMAIL)).hasSize(1);
        assertThat(service.of(ALICE, "calendar")).hasSize(1);
        assertThat(service.all(ALICE)).hasSize(2);
        // Both are the default of their own provider — the key is per provider too.
        assertThat(service.all(ALICE)).allSatisfy(c -> assertThat(c.isDefault()).isTrue());
    }

    private static Map<String, String> values(String... pairs) {
        Map<String, String> values = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) values.put(pairs[i], pairs[i + 1]);
        return values;
    }
}
