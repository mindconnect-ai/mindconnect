package ai.mindconnect.calendar;

import java.util.List;

/**
 * One entry in a calendar, as a screen needs it.
 *
 * <p>A deliberately flat view of two rich and different models: no recurrence
 * rule, no response status per attendee, no reminders. Recurrence in
 * particular is asked of both providers as <em>occurrences</em> — Graph's
 * {@code calendarView}, Google's {@code singleEvents} — so a weekly meeting
 * arrives as this week's slot rather than as a rule to expand here. That is
 * what somebody looking at a week means by it, and expanding an RRULE
 * correctly is a library, not a method.
 */
public record CalendarEvent(
        String id,
        String calendarId,
        String title,
        EventTime when,
        String location,
        String organiser,
        List<String> attendees,
        String notes,
        boolean cancelled) {

    public CalendarEvent {
        attendees = attendees == null ? List.of() : List.copyOf(attendees);
        if (title == null || title.isBlank()) title = "(no subject)";
    }

    public boolean hasLocation() {
        return location != null && !location.isBlank();
    }
}
