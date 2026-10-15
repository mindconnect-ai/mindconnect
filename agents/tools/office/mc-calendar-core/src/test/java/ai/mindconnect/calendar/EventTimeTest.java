package ai.mindconnect.calendar;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The one piece of calendar logic that is not a provider's habit: what "when" means. */
class EventTimeTest {

    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");

    @Test
    void an_event_is_timed_or_all_day_and_never_both() {
        assertThatThrownBy(() -> new EventTime(Instant.now(), null, LocalDate.now(), null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EventTime(null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void an_all_day_entry_is_the_same_day_everywhere() {
        EventTime birthday = EventTime.on(LocalDate.of(2026, 10, 14), null);

        assertThat(birthday.allDay()).isTrue();
        assertThat(birthday.dayIn(BERLIN)).isEqualTo(LocalDate.of(2026, 10, 14));
        assertThat(birthday.dayIn(ZoneId.of("Pacific/Auckland"))).isEqualTo(LocalDate.of(2026, 10, 14));
        assertThat(birthday.lastDayIn(BERLIN)).isEqualTo(LocalDate.of(2026, 10, 14));
    }

    @Test
    void a_timed_entry_is_on_the_day_the_reader_is_standing_in() {
        // 23:30 UTC on the 14th is already the 15th in Berlin.
        EventTime late = EventTime.at(Instant.parse("2026-10-14T23:30:00Z"), Instant.parse("2026-10-15T00:30:00Z"));

        assertThat(late.dayIn(ZoneId.of("UTC"))).isEqualTo(LocalDate.of(2026, 10, 14));
        assertThat(late.dayIn(BERLIN)).isEqualTo(LocalDate.of(2026, 10, 15));
    }

    @Test
    void an_appointment_that_ends_at_midnight_ends_on_the_day_before() {
        EventTime evening = EventTime.at(
                Instant.parse("2026-10-14T15:00:00Z"),    // 17:00 Berlin
                Instant.parse("2026-10-14T22:00:00Z"));   // 00:00 Berlin, next day

        assertThat(evening.lastDayIn(BERLIN)).isEqualTo(LocalDate.of(2026, 10, 14));
        assertThat(evening.covers(LocalDate.of(2026, 10, 15), BERLIN)).isFalse();
    }

    @Test
    void a_run_of_days_covers_every_one_of_them() {
        EventTime holiday = EventTime.on(LocalDate.of(2026, 7, 6), LocalDate.of(2026, 7, 10));

        assertThat(holiday.covers(LocalDate.of(2026, 7, 5), BERLIN)).isFalse();
        assertThat(holiday.covers(LocalDate.of(2026, 7, 6), BERLIN)).isTrue();
        assertThat(holiday.covers(LocalDate.of(2026, 7, 8), BERLIN)).isTrue();
        assertThat(holiday.covers(LocalDate.of(2026, 7, 10), BERLIN)).isTrue();
        assertThat(holiday.covers(LocalDate.of(2026, 7, 11), BERLIN)).isFalse();
    }

    @Test
    void an_all_day_entry_sorts_from_midnight_where_the_reader_is() {
        EventTime allDay = EventTime.on(LocalDate.of(2026, 10, 14), null);

        assertThat(allDay.startsIn(BERLIN)).isEqualTo(Instant.parse("2026-10-13T22:00:00Z"));
    }

    @Test
    void an_end_that_is_missing_is_the_start() {
        assertThat(EventTime.at(Instant.parse("2026-10-14T09:00:00Z"), null).end())
                .isEqualTo(Instant.parse("2026-10-14T09:00:00Z"));
        assertThat(EventTime.on(LocalDate.of(2026, 10, 14), null).endDate())
                .isEqualTo(LocalDate.of(2026, 10, 14));
    }
}
