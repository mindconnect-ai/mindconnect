package ai.mindconnect.user.service;

import ai.mindconnect.agent.UserId;
import ai.mindconnect.user.domain.Notification;
import ai.mindconnect.user.domain.NotificationId;
import ai.mindconnect.user.domain.NotificationLevel;
import ai.mindconnect.user.port.out.NotificationRepository;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

class NotificationServiceTest {

    private static final UserId ALICE = UserId.of("alice");
    private static final UserId BOB = UserId.of("bob");

    private final MovingClock clock = new MovingClock(Instant.parse("2026-09-18T08:00:00Z"));
    private final MapNotifications repository = new MapNotifications();
    private final NotificationService service = new NotificationService(repository, clock);

    @Test
    void the_same_condition_raised_twice_is_one_entry() {
        // A check runs on every sign-in and says the same thing every time.
        service.raise(ALICE, setupDraft());
        clock.advance(Duration.ofDays(1));
        service.raise(ALICE, setupDraft());

        assertThat(service.open(ALICE)).hasSize(1);
        assertThat(service.unreadCount(ALICE)).isEqualTo(1);
    }

    @Test
    void raising_again_does_not_make_a_read_entry_unread() {
        service.raise(ALICE, setupDraft());
        service.markAllRead(ALICE);

        service.raise(ALICE, setupDraft());

        assertThat(service.unreadCount(ALICE)).isZero();
        assertThat(service.open(ALICE)).hasSize(1);
    }

    @Test
    void a_dismissed_condition_is_not_raised_at_the_user_again() {
        // They said they know. Silence until the condition has actually gone away.
        Notification raised = service.raise(ALICE, setupDraft()).orElseThrow();
        service.dismiss(ALICE, raised.id());

        assertThat(service.raise(ALICE, setupDraft())).isEmpty();
        assertThat(service.open(ALICE)).isEmpty();
    }

    @Test
    void a_condition_that_comes_back_after_being_resolved_raises_a_fresh_notice() {
        Notification first = service.raise(ALICE, setupDraft()).orElseThrow();
        service.dismiss(ALICE, first.id());

        service.resolve(ALICE, setupDraft().key());        // the variable was set at last
        Optional<Notification> again = service.raise(ALICE, setupDraft());   // and removed again

        assertThat(again).isPresent();
        assertThat(again.orElseThrow().id()).isNotEqualTo(first.id());
        assertThat(service.open(ALICE)).hasSize(1);
    }

    @Test
    void a_draft_without_a_key_piles_up_because_nothing_says_it_is_the_same_thing() {
        Notification.Draft oneOff = Notification.Draft.of(null, NotificationLevel.INFO, "Import finished", null);

        service.raise(ALICE, oneOff);
        service.raise(ALICE, oneOff);

        assertThat(service.open(ALICE)).hasSize(2);
    }

    @Test
    void resolve_others_clears_what_a_check_has_stopped_reporting_and_keeps_the_rest() {
        service.raise(ALICE, draft("setup.tool-variable.MC_EMAIL_HOST"));
        service.raise(ALICE, draft("setup.tool-variable.MC_EMAIL_USER"));
        service.raise(ALICE, draft("setup.other.THING"));

        int cleared = service.resolveOthers(ALICE, "setup.tool-variable.",
                Set.of("setup.tool-variable.MC_EMAIL_USER"));

        assertThat(cleared).isEqualTo(1);
        assertThat(service.open(ALICE)).extracting(Notification::key)
                .containsExactlyInAnyOrder("setup.tool-variable.MC_EMAIL_USER", "setup.other.THING");
    }

    @Test
    void resolve_others_clears_a_dismissed_entry_too_so_the_condition_can_speak_again() {
        Notification raised = service.raise(ALICE, setupDraft()).orElseThrow();
        service.dismiss(ALICE, raised.id());

        service.resolveOthers(ALICE, "setup.tool-variable.", Set.of());

        assertThat(service.withKey(ALICE, setupDraft().key())).isEmpty();
        assertThat(service.raise(ALICE, setupDraft())).isPresent();
    }

    @Test
    void the_bell_counts_only_this_users_notices() {
        service.raise(ALICE, setupDraft());
        service.raise(BOB, setupDraft());

        assertThat(service.unreadCount(ALICE)).isEqualTo(1);
        assertThat(service.open(BOB)).hasSize(1);
        assertThat(service.dismiss(BOB, service.open(ALICE).get(0).id())).isFalse();
    }

    @Test
    void the_bell_insists_only_while_something_is_waiting_to_be_done() {
        service.raise(ALICE, Notification.Draft.of("a", NotificationLevel.INFO, "Something happened", null));
        assertThat(service.hasActionRequired(ALICE)).isFalse();

        service.raise(ALICE, setupDraft());
        assertThat(service.hasActionRequired(ALICE)).isTrue();

        service.dismissAll(ALICE);
        assertThat(service.hasActionRequired(ALICE)).isFalse();
    }

    @Test
    void the_list_reads_newest_first() {
        service.raise(ALICE, draft("first"));
        clock.advance(Duration.ofHours(2));
        service.raise(ALICE, draft("second"));

        assertThat(service.open(ALICE)).extracting(Notification::key).containsExactly("second", "first");
    }

    // ── fixtures ────────────────────────────────────────────────────────────

    private static Notification.Draft setupDraft() {
        return new Notification.Draft("setup.tool-variable.MC_EMAIL_HOST", NotificationLevel.ACTION_REQUIRED,
                "Still to set up: Mail server", "The email tools need it.", "Set it up", "/admin/profile");
    }

    private static Notification.Draft draft(String key) {
        return Notification.Draft.of(key, NotificationLevel.ACTION_REQUIRED, "Missing: " + key, null);
    }

    private static final class MapNotifications implements NotificationRepository {

        private final Map<NotificationId, Notification> byId = new ConcurrentHashMap<>();

        @Override public void save(Notification notification) { byId.put(notification.id(), notification); }

        @Override public Optional<Notification> findById(NotificationId id) {
            return Optional.ofNullable(byId.get(id));
        }

        @Override public List<Notification> findByUser(UserId userId) {
            return byId.values().stream().filter(n -> n.userId().equals(userId)).toList();
        }

        @Override public void deleteById(NotificationId id) { byId.remove(id); }
    }

    /** A clock a test moves by hand, so "newest first" and "a day later" mean something. */
    private static final class MovingClock extends Clock {

        private Instant now;

        MovingClock(Instant now) { this.now = now; }

        void advance(Duration by) { now = now.plus(by); }

        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
