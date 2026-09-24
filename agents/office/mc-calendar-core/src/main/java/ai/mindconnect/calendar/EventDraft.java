package ai.mindconnect.calendar;

import java.util.List;
import java.util.Objects;

/**
 * A new entry, on its way into a calendar — or what an existing one becomes.
 *
 * <p>Attendees are invited, which sends them mail — which is why the UI says
 * so beside the field rather than after the fact.
 *
 * @param reminders minutes before the start, see {@link Reminders}: null
 *                  leaves them alone (a new entry gets the calendar's
 *                  default, a changed one keeps its own), an empty list means
 *                  none. A store whose {@link CalendarStore#reminders()} is
 *                  {@link ReminderSupport#NONE} does not write them.
 */
public record EventDraft(
        String calendarId,
        String title,
        EventTime when,
        String location,
        String notes,
        List<String> attendees,
        List<Integer> reminders) {

    public EventDraft {
        Objects.requireNonNull(when, "when");
        attendees = attendees == null ? List.of() : List.copyOf(attendees);
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("An entry needs a title.");
        }
        reminders = Reminders.of(reminders);
    }

    /** A draft that leaves the reminders alone. */
    public EventDraft(String calendarId, String title, EventTime when, String location, String notes,
                      List<String> attendees) {
        this(calendarId, title, when, location, notes, attendees, null);
    }

    /** This draft with {@code reminders} instead. */
    public EventDraft withReminders(List<Integer> reminders) {
        return new EventDraft(calendarId, title, when, location, notes, attendees, reminders);
    }
}
