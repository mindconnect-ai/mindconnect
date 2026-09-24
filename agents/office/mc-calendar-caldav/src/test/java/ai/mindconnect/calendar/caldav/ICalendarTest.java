package ai.mindconnect.calendar.caldav;

import ai.mindconnect.calendar.CalendarEvent;
import ai.mindconnect.calendar.EventDraft;
import ai.mindconnect.calendar.EventTime;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The iCalendar reader and patcher on their own: lengths, and what a change keeps. */
class ICalendarTest {

    private static CalendarEvent only(String... lines) {
        String ical = "BEGIN:VCALENDAR\r\nBEGIN:VEVENT\r\nUID:x\r\nSUMMARY:x\r\n"
                + String.join("\r\n", lines) + "\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n";
        List<CalendarEvent> events = ICalendar.events(ical, "cal", "x");
        assertThat(events).hasSize(1);
        return events.get(0);
    }

    @Test
    void an_appointment_that_says_how_long_it_is_ends_after_that() {
        CalendarEvent call = only("DTSTART:20260924T080000Z", "DURATION:PT30M");
        assertThat(call.when().end()).isEqualTo(Instant.parse("2026-09-24T08:30:00Z"));

        CalendarEvent workshop = only("DTSTART:20260924T080000Z", "DURATION:P1DT2H30M");
        assertThat(workshop.when().end()).isEqualTo(Instant.parse("2026-09-25T10:30:00Z"));

        CalendarEvent week = only("DTSTART:20260924T080000Z", "DURATION:P1W");
        assertThat(week.when().end()).isEqualTo(Instant.parse("2026-10-01T08:00:00Z"));

        // A day is the same time on the next date, across the change back from summer time.
        CalendarEvent overnight = only("DTSTART;TZID=Europe/Zurich:20261024T100000", "DURATION:P1D");
        assertThat(overnight.when().start()).isEqualTo(Instant.parse("2026-10-24T08:00:00Z"));
        assertThat(overnight.when().end()).isEqualTo(Instant.parse("2026-10-25T09:00:00Z"));
    }

    @Test
    void an_all_day_appointment_with_a_duration_covers_its_days() {
        CalendarEvent trip = only("DTSTART;VALUE=DATE:20260923", "DURATION:P3D");
        assertThat(trip.when().allDay()).isTrue();
        assertThat(trip.when().startDate()).isEqualTo(LocalDate.of(2026, 9, 23));
        assertThat(trip.when().endDate()).isEqualTo(LocalDate.of(2026, 9, 25));

        CalendarEvent fortnight = only("DTSTART;VALUE=DATE:20260923", "DURATION:P2W");
        assertThat(fortnight.when().endDate()).isEqualTo(LocalDate.of(2026, 10, 6));
    }

    @Test
    void neither_an_end_nor_a_duration_is_an_hour_or_a_day() {
        assertThat(only("DTSTART:20260924T080000Z").when().end()).isEqualTo(Instant.parse("2026-09-24T09:00:00Z"));
        assertThat(only("DTSTART;VALUE=DATE:20260923").when().endDate()).isEqualTo(LocalDate.of(2026, 9, 23));
        assertThat(ICalendar.length("P")).isNull();
        assertThat(ICalendar.length("PT")).isNull();
        assertThat(ICalendar.length("soon")).isNull();
    }

    @Test
    void a_change_that_does_not_move_it_keeps_its_duration() {
        String original = "BEGIN:VCALENDAR\r\nBEGIN:VEVENT\r\nUID:x\r\nSUMMARY:Call\r\n"
                + "DTSTART:20260924T080000Z\r\nDURATION:PT30M\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n";
        CalendarEvent was = ICalendar.master(original, "cal", "x");

        String renamed = ICalendar.patch(original, new EventDraft(null, "Call with Anna", was.when(),
                null, null, List.of()));

        assertThat(renamed).contains("DTSTART:20260924T080000Z\r\nDURATION:PT30M").doesNotContain("DTEND")
                .contains("SUMMARY:Call with Anna").contains("SEQUENCE:1");
        assertThat(ICalendar.master(renamed, "cal", "x").when()).isEqualTo(was.when());
    }

    @Test
    void a_change_that_moves_it_writes_start_and_end_instead_of_the_duration() {
        String original = "BEGIN:VCALENDAR\r\nBEGIN:VEVENT\r\nUID:x\r\nSUMMARY:Call\r\n"
                + "DTSTART:20260924T080000Z\r\nDURATION:PT30M\r\nLOCATION:Phone\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n";

        String moved = ICalendar.patch(original, new EventDraft(null, "Call",
                EventTime.at(Instant.parse("2026-09-25T13:00:00Z"), Instant.parse("2026-09-25T13:45:00Z")),
                "Phone", null, List.of()));

        assertThat(moved).contains("DTSTART:20260925T130000Z").contains("DTEND:20260925T134500Z")
                .doesNotContain("DURATION").doesNotContain("20260924T080000Z")
                .contains("SUMMARY:Call\r\n").contains("LOCATION:Phone");
    }
}
