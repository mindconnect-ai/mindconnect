package ai.mindconnect.user.service;

import ai.mindconnect.agent.UserId;
import ai.mindconnect.user.domain.Preferences;
import ai.mindconnect.user.port.out.PreferenceRepository;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PreferenceServiceTest {

    private static final UserId ALICE = UserId.of("alice");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-21T10:00:00Z"), ZoneOffset.UTC);

    /** A map, counting the writes — the point of several tests is that nothing was written. */
    static class Repo implements PreferenceRepository {
        final Map<String, Preferences> docs = new HashMap<>();
        int writes;

        @Override public Optional<Preferences> find(UserId u, String s) { return Optional.ofNullable(docs.get(s + "/" + u.value())); }
        @Override public List<Preferences> findByUser(UserId u) { return docs.values().stream().filter(p -> p.userId().equals(u)).toList(); }
        @Override public void save(Preferences p) { writes++; docs.put(p.scope() + "/" + p.userId().value(), p); }
        @Override public void delete(UserId u, String s) { writes++; docs.remove(s + "/" + u.value()); }
    }

    private final Repo repo = new Repo();
    private final PreferenceService service = new PreferenceService(repo, CLOCK);

    @Test
    void a_scope_that_remembers_nothing_is_empty_not_missing() {
        Preferences none = service.get(ALICE, "office.email");

        assertThat(none.isEmpty()).isTrue();
        assertThat(service.get(ALICE, "office.email", "folder")).isEmpty();
    }

    @Test
    void changing_one_value_keeps_the_others_of_the_scope() {
        service.merge(ALICE, "office.email", Map.of("mailbox", "email.work", "folder", "INBOX"));
        service.put(ALICE, "office.email", "folder", "Archive");

        Preferences now = service.get(ALICE, "office.email");
        assertThat(now.values()).containsExactly(Map.entry("folder", "Archive"), Map.entry("mailbox", "email.work"));
        assertThat(now.updatedAt()).isEqualTo(CLOCK.instant());
    }

    @Test
    void a_write_that_changes_nothing_writes_nothing() {
        service.put(ALICE, "office.email", "folder", "INBOX");
        service.put(ALICE, "office.email", "folder", "INBOX");
        service.merge(ALICE, "office.email", Map.of("folder", "INBOX"));

        assertThat(repo.writes).isEqualTo(1);
    }

    @Test
    void null_removes_a_key_and_the_last_one_removes_the_document() {
        service.merge(ALICE, "office.todos", Map.of("list", "inbox", "showDone", "true"));
        service.put(ALICE, "office.todos", "showDone", null);
        assertThat(service.get(ALICE, "office.todos").values()).containsOnlyKeys("list");

        service.put(ALICE, "office.todos", "list", null);
        assertThat(repo.docs).isEmpty();
    }

    @Test
    void users_and_scopes_are_kept_apart_and_a_scope_can_be_forgotten() {
        service.put(ALICE, "office.email", "folder", "INBOX");
        service.put(ALICE, "office.calendar", "view", "week");
        service.put(UserId.of("bob"), "office.email", "folder", "Sent");

        assertThat(service.all(ALICE)).extracting(Preferences::scope)
                .containsExactlyInAnyOrder("office.email", "office.calendar");
        service.clear(ALICE, "office.email");
        assertThat(service.get(ALICE, "office.email").isEmpty()).isTrue();
        assertThat(service.get(UserId.of("bob"), "office.email", "folder")).contains("Sent");
    }

    @Test
    void scopes_keys_and_values_are_bounded() {
        assertThatThrownBy(() -> service.put(ALICE, "../etc", "k", "v")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.put(ALICE, "Office", "k", "v")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.put(ALICE, "office", "a key", "v")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.put(ALICE, "office", "k", "x".repeat(PreferenceService.MAX_VALUE + 1)))
                .isInstanceOf(IllegalArgumentException.class);

        Map<String, String> many = new HashMap<>();
        for (int i = 0; i <= PreferenceService.MAX_KEYS; i++) many.put("k" + i, "v");
        assertThatThrownBy(() -> service.merge(ALICE, "office", many)).isInstanceOf(IllegalArgumentException.class);
        assertThat(repo.writes).isZero();
    }
}
