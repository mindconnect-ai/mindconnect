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

    // ── reminders ───────────────────────────────────────────────────────────

    @Test
    void every_alarm_before_the_start_is_a_reminder_and_nothing_of_an_alarm_is_the_appointments() {
        CalendarEvent call = only("DTSTART:20260924T080000Z",
                "BEGIN:VALARM", "ACTION:DISPLAY", "DESCRIPTION:Reminder", "TRIGGER:-PT1H", "END:VALARM",
                "BEGIN:VALARM", "ACTION:EMAIL", "ATTENDEE:mailto:me@example.com", "SUMMARY:x",
                "DESCRIPTION:Mail", "TRIGGER;RELATED=START:-PT10M", "DURATION:PT5M", "REPEAT:2", "END:VALARM",
                "BEGIN:VALARM", "ACTION:AUDIO", "TRIGGER:-P1D", "END:VALARM",
                "BEGIN:VALARM", "ACTION:DISPLAY", "DESCRIPTION:now", "TRIGGER:PT0S", "END:VALARM");

        assertThat(call.reminders()).containsExactly(0, 10, 60, 1440);
        // The alarms' DESCRIPTION, ATTENDEE and DURATION belong to them, not to the appointment.
        assertThat(call.notes()).isNull();
        assertThat(call.attendees()).isEmpty();
        assertThat(call.when().end()).isEqualTo(Instant.parse("2026-09-24T09:00:00Z"));
    }

    @Test
    void alarms_that_are_not_minutes_before_the_start_are_not_listed() {
        CalendarEvent call = only("DTSTART:20260924T080000Z", "DESCRIPTION:Agenda",
                "BEGIN:VALARM", "ACTION:DISPLAY", "TRIGGER;VALUE=DATE-TIME:20260924T070000Z", "END:VALARM",
                "BEGIN:VALARM", "ACTION:DISPLAY", "TRIGGER;RELATED=END:-PT5M", "END:VALARM",
                "BEGIN:VALARM", "ACTION:DISPLAY", "TRIGGER:PT15M", "END:VALARM",
                "BEGIN:VALARM", "ACTION:NONE", "TRIGGER;VALUE=DATE-TIME:19760401T005545Z", "END:VALARM",
                "BEGIN:VALARM", "ACTION:DISPLAY", "TRIGGER:-PT30M", "END:VALARM");

        assertThat(call.reminders()).containsExactly(30);
        assertThat(call.notes()).isEqualTo("Agenda");
        assertThat(only("DTSTART:20260924T080000Z").reminders()).as("no alarm, no reminder").isEmpty();
    }

    @Test
    void a_trigger_is_read_as_minutes_before_the_start() {
        assertThat(ICalendar.minutesBefore("TRIGGER:-PT15M")).isEqualTo(15);
        assertThat(ICalendar.minutesBefore("TRIGGER:-P1DT2H")).isEqualTo(1560);
        assertThat(ICalendar.minutesBefore("TRIGGER:-P1W")).isEqualTo(10080);
        assertThat(ICalendar.minutesBefore("TRIGGER;RELATED=START:-PT0M")).isEqualTo(0);
        assertThat(ICalendar.minutesBefore("TRIGGER:PT5M")).isNull();
        assertThat(ICalendar.minutesBefore("TRIGGER:soon")).isNull();
        assertThat(ICalendar.minutesBefore(null)).isNull();
    }

    @Test
    void a_new_appointment_carries_a_display_alarm_per_reminder_even_all_day() {
        String written = ICalendar.write(new EventDraft(null, "Geburtstag Anna",
                EventTime.on(LocalDate.of(2026, 9, 24), null), null, null, List.of(), List.of(60, 10)),
                "x", java.time.ZoneOffset.UTC);

        assertThat(written).contains("BEGIN:VALARM\r\nACTION:DISPLAY\r\nDESCRIPTION:Geburtstag Anna\r\n"
                + "TRIGGER:-PT10M\r\nEND:VALARM\r\nBEGIN:VALARM\r\nACTION:DISPLAY\r\n"
                + "DESCRIPTION:Geburtstag Anna\r\nTRIGGER:-PT60M\r\nEND:VALARM\r\nEND:VEVENT");
        assertThat(ICalendar.master(written, "cal", "x").reminders()).containsExactly(10, 60);
        assertThat(ICalendar.master(written, "cal", "x").notes()).isNull();

        String none = ICalendar.write(new EventDraft(null, "Call",
                EventTime.on(LocalDate.of(2026, 9, 24), null), null, null, List.of(), List.of()),
                "y", java.time.ZoneOffset.UTC);
        String untouched = ICalendar.write(new EventDraft(null, "Call",
                EventTime.on(LocalDate.of(2026, 9, 24), null), null, null, List.of()),
                "z", java.time.ZoneOffset.UTC);
        assertThat(none).doesNotContain("VALARM");
        assertThat(untouched).doesNotContain("VALARM");
    }

    private static final String WITH_ALARMS = "BEGIN:VCALENDAR\r\nBEGIN:VEVENT\r\nUID:x\r\nSUMMARY:Call\r\n"
            + "DTSTART:20260924T080000Z\r\nDTEND:20260924T090000Z\r\n"
            + "BEGIN:VALARM\r\nACTION:DISPLAY\r\nDESCRIPTION:Old\r\nTRIGGER:-PT15M\r\nEND:VALARM\r\n"
            + "BEGIN:VALARM\r\nACTION:EMAIL\r\nSUMMARY:Old\r\nDESCRIPTION:Old\r\n"
            + "ATTENDEE:mailto:me@example.com\r\nTRIGGER:-P1D\r\nEND:VALARM\r\n"
            + "END:VEVENT\r\nEND:VCALENDAR\r\n";

    @Test
    void reminders_a_change_names_replace_every_alarm() {
        CalendarEvent was = ICalendar.master(WITH_ALARMS, "cal", "x");
        assertThat(was.reminders()).containsExactly(15, 1440);

        String changed = ICalendar.patch(WITH_ALARMS, new EventDraft(null, "Call", was.when(), null, null,
                List.of(), List.of(5, 60)));

        assertThat(changed).doesNotContain("-PT15M").doesNotContain("-P1D").doesNotContain("ACTION:EMAIL")
                .contains("TRIGGER:-PT5M").contains("TRIGGER:-PT60M")
                .containsOnlyOnce("END:VEVENT").endsWith("END:VALARM\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n");
        CalendarEvent now = ICalendar.master(changed, "cal", "x");
        assertThat(now.reminders()).containsExactly(5, 60);
        assertThat(now.when()).isEqualTo(was.when());
        assertThat(now.attendees()).as("the e-mail alarm's recipient was never an attendee").isEmpty();
    }

    @Test
    void no_reminders_remove_every_alarm_and_null_keeps_them() {
        CalendarEvent was = ICalendar.master(WITH_ALARMS, "cal", "x");

        String cleared = ICalendar.patch(WITH_ALARMS, new EventDraft(null, "Call", was.when(), null, null,
                List.of(), List.of()));
        String renamed = ICalendar.patch(WITH_ALARMS, new EventDraft(null, "Call with Anna", was.when(), null,
                null, List.of()));

        assertThat(cleared).doesNotContain("VALARM");
        assertThat(ICalendar.master(cleared, "cal", "x").reminders()).isEmpty();
        assertThat(renamed).contains("TRIGGER:-PT15M").contains("TRIGGER:-P1D").contains("SUMMARY:Call with Anna");
        assertThat(ICalendar.master(renamed, "cal", "x").reminders()).containsExactly(15, 1440);
    }

    @Test
    void only_the_series_alarms_are_replaced_not_those_of_a_changed_occurrence() {
        String series = "BEGIN:VCALENDAR\r\n"
                + "BEGIN:VEVENT\r\nUID:w\r\nSUMMARY:Jour fixe\r\nDTSTART:20260924T080000Z\r\n"
                + "RRULE:FREQ=WEEKLY\r\n"
                + "BEGIN:VALARM\r\nACTION:DISPLAY\r\nDESCRIPTION:x\r\nTRIGGER:-PT15M\r\nEND:VALARM\r\n"
                + "END:VEVENT\r\n"
                + "BEGIN:VEVENT\r\nUID:w\r\nRECURRENCE-ID:20261001T080000Z\r\nSUMMARY:Jour fixe\r\n"
                + "DTSTART:20261001T090000Z\r\n"
                + "BEGIN:VALARM\r\nACTION:DISPLAY\r\nDESCRIPTION:x\r\nTRIGGER:-PT45M\r\nEND:VALARM\r\n"
                + "END:VEVENT\r\nEND:VCALENDAR\r\n";
        CalendarEvent was = ICalendar.master(series, "cal", "w");

        String changed = ICalendar.patch(series, new EventDraft(null, was.title(), was.when(), null, null,
                List.of(), List.of(10)));

        assertThat(changed).doesNotContain("-PT15M").contains("TRIGGER:-PT10M").contains("TRIGGER:-PT45M")
                .contains("RRULE:FREQ=WEEKLY");
        assertThat(ICalendar.master(changed, "cal", "w").reminders()).containsExactly(10);
    }
}
