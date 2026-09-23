package ai.mindconnect.calendar;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Objects;

/**
 * When something is: either a moment that ends at another moment, or a whole
 * day — or run of days — that has no moment at all.
 *
 * <p>The distinction is not a flag on a timestamp. "14 October, all day" is
 * the same entry in Berlin and in Tokyo; "14 October 09:00" is not. Both
 * providers keep the two apart (Graph with {@code isAllDay}, Google by
 * returning {@code date} instead of {@code dateTime}), and a screen that
 * collapsed them into one instant would move a birthday across midnight for
 * anyone east of the server.
 *
 * <p>Exactly one of the two pairs is set.
 */
public record EventTime(Instant start, Instant end, LocalDate startDate, LocalDate endDate) {

    public EventTime {
        boolean timed = start != null;
        boolean dated = startDate != null;
        if (timed == dated) {
            throw new IllegalArgumentException("An event is either timed or all-day, not both and not neither");
        }
        if (timed && end == null) end = start;
        if (dated && endDate == null) endDate = startDate;
    }

    /** An appointment: from one moment to another. */
    public static EventTime at(Instant start, Instant end) {
        return new EventTime(Objects.requireNonNull(start, "start"), end, null, null);
    }

    /** A whole day, or a run of them. {@code last} is inclusive — the last day it covers. */
    public static EventTime on(LocalDate first, LocalDate last) {
        return new EventTime(null, null, Objects.requireNonNull(first, "first"), last);
    }

    public boolean allDay() {
        return startDate != null;
    }

    /** The day this begins on, as somebody in {@code zone} would say it. */
    public LocalDate dayIn(ZoneId zone) {
        return allDay() ? startDate : start.atZone(zone).toLocalDate();
    }

    /** The last day this covers, inclusive, as somebody in {@code zone} would say it. */
    public LocalDate lastDayIn(ZoneId zone) {
        if (allDay()) return endDate;
        ZonedDateTime finish = end.atZone(zone);
        // An appointment that ends at midnight ends on the day before: nobody
        // calls 17:00–24:00 a two-day meeting.
        LocalDate day = finish.toLocalDate();
        return finish.toLocalTime().equals(java.time.LocalTime.MIDNIGHT) && finish.toInstant().isAfter(start)
                ? day.minusDays(1) : day;
    }

    /** True when this covers any part of {@code day} in {@code zone}. */
    public boolean covers(LocalDate day, ZoneId zone) {
        return !day.isBefore(dayIn(zone)) && !day.isAfter(lastDayIn(zone));
    }

    /** The moment it starts, for sorting — midnight in {@code zone} for an all-day entry. */
    public Instant startsIn(ZoneId zone) {
        return allDay() ? startDate.atStartOfDay(zone).toInstant() : start;
    }
}
