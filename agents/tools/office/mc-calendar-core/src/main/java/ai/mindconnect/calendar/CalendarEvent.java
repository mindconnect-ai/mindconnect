package ai.mindconnect.calendar;

import java.util.List;

/**
 * One entry in a calendar, as a screen needs it.
 *
 * <p>A deliberately flat view of two rich and different models: no recurrence
 * rule, no response status per attendee. Recurrence in particular is asked of
 * both providers as <em>occurrences</em> — Graph's {@code calendarView},
 * Google's {@code singleEvents} — so a weekly meeting arrives as this week's
 * slot rather than as a rule to expand here. That is what somebody looking at
 * a week means by it, and expanding an RRULE correctly is a library, not a
 * method.
 *
 * @param reminders minutes before the start, shortest first (see {@link
 *                  Reminders}); empty when the entry has none, null when the
 *                  store does not say — it reads no reminders, or the entry
 *                  follows a calendar default it did not look up
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
        boolean cancelled,
        List<Integer> reminders) {

    public CalendarEvent {
        attendees = attendees == null ? List.of() : List.copyOf(attendees);
        if (title == null || title.isBlank()) title = "(no subject)";
        reminders = Reminders.of(reminders);
    }

    /** An entry whose reminders the store does not say. */
    public CalendarEvent(String id, String calendarId, String title, EventTime when, String location,
                         String organiser, List<String> attendees, String notes, boolean cancelled) {
        this(id, calendarId, title, when, location, organiser, attendees, notes, cancelled, null);
    }

    public boolean hasLocation() {
        return location != null && !location.isBlank();
    }
}
