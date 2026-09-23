package ai.mindconnect.calendar;

import java.util.List;
import java.util.Objects;

/**
 * A new entry, on its way into a calendar.
 *
 * <p>Attendees are invited, which sends them mail — which is why the UI says
 * so beside the field rather than after the fact.
 */
public record EventDraft(
        String calendarId,
        String title,
        EventTime when,
        String location,
        String notes,
        List<String> attendees) {

    public EventDraft {
        Objects.requireNonNull(when, "when");
        attendees = attendees == null ? List.of() : List.copyOf(attendees);
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("An entry needs a title.");
        }
    }
}
