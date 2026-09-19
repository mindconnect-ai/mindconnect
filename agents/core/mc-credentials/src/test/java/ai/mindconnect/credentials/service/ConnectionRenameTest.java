package ai.mindconnect.credentials.service;

import ai.mindconnect.agent.UserId;
import ai.mindconnect.credentials.adapter.memory.InMemoryConnectionRepository;
import ai.mindconnect.credentials.domain.Connection;
import ai.mindconnect.credentials.domain.ConnectionId;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** A new name, and nothing else about the connection moves. */
class ConnectionRenameTest {

    private final ConnectionService service = new ConnectionService(new InMemoryConnectionRepository());
    private final UserId david = UserId.of("david");

    @Test
    void renaming_keeps_the_key_and_the_credentials() {
        Connection made = service.add(david, "microsoft", "Microsoft account",
                Map.of("user", "david", "password", "secret"), Set.of("password"));

        Connection renamed = service.rename(david, made.id(), "david@example.com").orElseThrow();

        assertThat(renamed.label()).isEqualTo("david@example.com");
        assertThat(renamed.key()).isEqualTo(made.key());
        assertThat(renamed.credentials()).isEqualTo(made.credentials());
        assertThat(renamed.settings()).isEqualTo(made.settings());
        assertThat(service.find(david, made.id()).orElseThrow().label()).isEqualTo("david@example.com");
    }

    @Test
    void a_blank_name_changes_nothing_and_somebody_elses_connection_is_not_found() {
        Connection made = service.add(david, "microsoft", "Microsoft account", Map.of(), Set.of());

        assertThat(service.rename(david, made.id(), "  ").orElseThrow().label()).isEqualTo("Microsoft account");
        assertThat(service.rename(UserId.of("somebody"), made.id(), "theirs")).isEmpty();
        assertThat(service.rename(david, ConnectionId.random(), "gone")).isEmpty();
    }
}
