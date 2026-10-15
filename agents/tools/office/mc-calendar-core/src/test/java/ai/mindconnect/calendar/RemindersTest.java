package ai.mindconnect.calendar;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Reminders as minutes before the start, and what each kind of calendar keeps of them. */
class RemindersTest {

    @Test
    void reminders_are_kept_once_each_and_shortest_first() {
        assertThat(Reminders.of(List.of(60, 10, 60))).containsExactly(10, 60);
        assertThat(Reminders.of(List.of())).isEmpty();
        assertThat(Reminders.of(null)).isNull();
        assertThatThrownBy(() -> Reminders.of(List.of(10, -5))).hasMessageContaining("-5");
        assertThatThrownBy(() -> Reminders.of(Arrays.asList(10, null))).hasMessageContaining("0 or more");
    }

    @Test
    void reminders_are_described_the_way_a_person_says_them() {
        assertThat(Reminders.describe(List.of(10))).isEqualTo("10 minutes before");
        assertThat(Reminders.describe(List.of(10, 60))).isEqualTo("10 minutes and 1 hour before");
        assertThat(Reminders.describe(List.of(1, 90, 120, 1440, 10080)))
                .isEqualTo("1 minute, 90 minutes, 2 hours, 1 day and 1 week before");
        assertThat(Reminders.describe(List.of(0))).isEqualTo("at the start");
        assertThat(Reminders.describe(List.of(0, 15))).isEqualTo("at the start and 15 minutes before");
        assertThat(Reminders.describe(List.of())).isEqualTo("no reminder");
        assertThat(Reminders.describe(null)).isNull();
    }

    @Test
    void a_draft_leaves_reminders_alone_unless_it_names_them() {
        EventTime day = EventTime.on(LocalDate.of(2026, 9, 24), null);

        assertThat(new EventDraft(null, "Urlaub", day, null, null, null).reminders()).isNull();
        assertThat(new EventDraft(null, "Urlaub", day, null, null, null, List.of(60, 10)).reminders())
                .containsExactly(10, 60);
        assertThat(new EventDraft(null, "Urlaub", day, null, null, null).withReminders(List.of()).reminders())
                .isEmpty();
        assertThat(new CalendarEvent("e", "c", "t", day, null, null, null, null, false).reminders()).isNull();
    }

    @Test
    void a_store_that_writes_no_reminders_refuses_them_but_not_leaving_them_alone() {
        assertThat(ReminderSupport.NONE.writes()).isFalse();
        assertThat(ReminderSupport.NONE.fit(null).kept()).isNull();
        assertThatThrownBy(() -> ReminderSupport.NONE.fit(List.of(10)))
                .isInstanceOf(CalendarStoreException.class).hasMessageContaining("does not take reminders");
    }

    @Test
    void too_many_or_too_far_ahead_is_refused_with_the_limit() {
        ReminderSupport google = ReminderSupport.upTo(5, 40_320);

        assertThat(google.fit(List.of(10, 60, 1440, 2880, 40_320)).kept()).hasSize(5);
        assertThat(google.fit(List.of()).kept()).isEmpty();
        assertThatThrownBy(() -> google.fit(List.of(1, 2, 3, 4, 5, 6)))
                .isInstanceOf(CalendarStoreException.class)
                .hasMessageContaining("at most 5 reminders").hasMessageContaining("not 6");
        assertThatThrownBy(() -> google.fit(List.of(10, 40_321)))
                .isInstanceOf(CalendarStoreException.class)
                .hasMessageContaining("at most 40320 minutes (4 weeks)").hasMessageContaining("not 40321");
    }

    @Test
    void a_calendar_with_one_reminder_keeps_the_one_closest_to_the_start() {
        ReminderSupport outlook = ReminderSupport.onlyOne(Integer.MAX_VALUE);

        ReminderSupport.Fit fit = outlook.fit(List.of(60, 10, 1440));

        assertThat(fit.kept()).containsExactly(10);
        assertThat(fit.dropped()).containsExactly(60, 1440);
        assertThat(outlook.fit(List.of()).kept()).isEmpty();
        assertThat(outlook.fit(List.of(15)).dropped()).isEmpty();
    }

    @Test
    void any_takes_what_it_is_given() {
        ReminderSupport.Fit fit = ReminderSupport.ANY.fit(List.of(0, 5, 10, 15, 30, 60, 1440, 100_000));

        assertThat(fit.kept()).hasSize(8);
        assertThat(fit.dropped()).isEmpty();
    }
}
