package ai.mindconnect.calendar.caldav;

import ai.mindconnect.calendar.CalendarEvent;
import ai.mindconnect.calendar.EventDraft;
import ai.mindconnect.calendar.EventTime;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * As much of iCalendar as an appointment is: reading a {@code VEVENT} into a
 * {@link CalendarEvent} and writing one back.
 *
 * <p><b>Not a calendar library.</b> Recurrence rules, alarms, time-zone
 * definitions and journals are left where they are: a recurring appointment is
 * read as the occurrence the server sent, an unknown line survives a
 * round-trip only if the caller keeps the original — which is why changing an
 * appointment rewrites it from the {@link EventDraft} rather than patching
 * lines. The reader is deliberately forgiving; every provider writes this
 * format a little differently.
 */
final class ICalendar {

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'", Locale.ROOT).withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter DAY =
            DateTimeFormatter.ofPattern("yyyyMMdd", Locale.ROOT);
    private static final DateTimeFormatter LOCAL =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss", Locale.ROOT);

    private ICalendar() { }

    // ── reading ─────────────────────────────────────────────────────────────

    /**
     * The appointments in one {@code VCALENDAR} text.
     *
     * @param calendarId what the events are filed under here
     * @param id         the id this resource has for the store (its href), used
     *                   when the {@code VEVENT} carries no {@code UID}
     */
    static List<CalendarEvent> events(String ical, String calendarId, String id) {
        List<CalendarEvent> events = new ArrayList<>();
        for (String block : blocks(unfold(ical))) {
            CalendarEvent event = event(block, calendarId, id);
            if (event != null) events.add(event);
        }
        return events;
    }

    private static CalendarEvent event(String block, String calendarId, String id) {
        String uid = value(block, "UID");
        String summary = value(block, "SUMMARY");
        EventTime when = when(block);
        if (when == null) return null;
        List<String> attendees = new ArrayList<>();
        for (String line : lines(block, "ATTENDEE")) {
            String address = address(line);
            if (address != null) attendees.add(address);
        }
        String organiser = address(firstLine(block, "ORGANIZER"));
        boolean cancelled = "CANCELLED".equalsIgnoreCase(value(block, "STATUS"));
        return new CalendarEvent(uid == null || uid.isBlank() ? id : uid, calendarId,
                summary == null || summary.isBlank() ? "(no title)" : text(summary),
                when, text(value(block, "LOCATION")), organiser, attendees,
                text(value(block, "DESCRIPTION")), cancelled);
    }

    /** {@code DTSTART}/{@code DTEND} as a period — a day, or a time and its end. */
    private static EventTime when(String block) {
        String start = firstLine(block, "DTSTART");
        if (start == null) return null;
        String end = firstLine(block, "DTEND");
        if (isDate(start)) {
            LocalDate first = day(valueOf(start));
            if (first == null) return null;
            // iCalendar's DTEND of an all-day event is the morning after it.
            LocalDate afterLast = end == null ? null : day(valueOf(end));
            LocalDate last = afterLast == null || !afterLast.isAfter(first) ? first : afterLast.minusDays(1);
            return EventTime.on(first, last);
        }
        Instant from = instant(start);
        if (from == null) return null;
        Instant to = end == null ? null : instant(end);
        return EventTime.at(from, to == null || !to.isAfter(from) ? from.plusSeconds(3600) : to);
    }

    // ── writing ─────────────────────────────────────────────────────────────

    /** {@code draft} as a whole {@code VCALENDAR}, ready to be PUT. */
    static String write(EventDraft draft, String uid, ZoneId zone) {
        StringBuilder out = new StringBuilder()
                .append("BEGIN:VCALENDAR\r\n")
                .append("VERSION:2.0\r\n")
                .append("PRODID:-//mindconnect//calendar//EN\r\n")
                .append("BEGIN:VEVENT\r\n")
                .append("UID:").append(uid).append("\r\n")
                .append("DTSTAMP:").append(STAMP.format(Instant.now())).append("\r\n");
        EventTime when = draft.when();
        if (when.allDay()) {
            out.append("DTSTART;VALUE=DATE:").append(DAY.format(when.startDate())).append("\r\n")
                    .append("DTEND;VALUE=DATE:").append(DAY.format(when.endDate().plusDays(1))).append("\r\n");
        } else {
            out.append("DTSTART:").append(STAMP.format(when.start())).append("\r\n")
                    .append("DTEND:").append(STAMP.format(when.end())).append("\r\n");
        }
        line(out, "SUMMARY", draft.title());
        line(out, "LOCATION", draft.location());
        line(out, "DESCRIPTION", draft.notes());
        for (String attendee : draft.attendees() == null ? List.<String>of() : draft.attendees()) {
            if (attendee != null && !attendee.isBlank()) {
                out.append("ATTENDEE;ROLE=REQ-PARTICIPANT:mailto:").append(attendee.strip()).append("\r\n");
            }
        }
        return out.append("END:VEVENT\r\n").append("END:VCALENDAR\r\n").toString();
    }

    private static void line(StringBuilder out, String name, String value) {
        if (value == null || value.isBlank()) return;
        out.append(name).append(':')
                .append(value.replace("\\", "\\\\").replace("\n", "\\n").replace(",", "\\,").replace(";", "\\;"))
                .append("\r\n");
    }

    // ── the format's own quirks ─────────────────────────────────────────────

    /** Lines are folded at 75 characters and continue with a space; put them back together. */
    static String unfold(String ical) {
        return ical == null ? "" : ical.replace("\r\n", "\n").replaceAll("\n[ \t]", "");
    }

    /** The {@code VEVENT}s, without the calendar around them. */
    private static List<String> blocks(String ical) {
        List<String> blocks = new ArrayList<>();
        int at = 0;
        while (true) {
            int start = ical.indexOf("BEGIN:VEVENT", at);
            if (start < 0) break;
            int end = ical.indexOf("END:VEVENT", start);
            if (end < 0) break;
            blocks.add(ical.substring(start, end));
            at = end + 1;
        }
        return blocks;
    }

    private static List<String> lines(String block, String name) {
        List<String> found = new ArrayList<>();
        for (String line : block.split("\n")) {
            String trimmed = line.strip();
            if (trimmed.regionMatches(true, 0, name, 0, name.length())) {
                int after = name.length();
                if (after < trimmed.length() && (trimmed.charAt(after) == ':' || trimmed.charAt(after) == ';')) {
                    found.add(trimmed);
                }
            }
        }
        return found;
    }

    private static String firstLine(String block, String name) {
        List<String> found = lines(block, name);
        return found.isEmpty() ? null : found.get(0);
    }

    private static String value(String block, String name) {
        String line = firstLine(block, name);
        return line == null ? null : valueOf(line);
    }

    /** What is after the colon — the parameters between the name and it are not values. */
    private static String valueOf(String line) {
        int colon = line.indexOf(':');
        return colon < 0 ? "" : line.substring(colon + 1).strip();
    }

    /** {@code \n}, {@code \,} and {@code \;} are escapes in this format. */
    private static String text(String value) {
        if (value == null) return null;
        String plain = value.replace("\\n", "\n").replace("\\N", "\n")
                .replace("\\,", ",").replace("\\;", ";").replace("\\\\", "\\");
        return plain.isBlank() ? null : plain;
    }

    private static boolean isDate(String line) {
        String upper = line.toUpperCase(Locale.ROOT);
        return upper.contains("VALUE=DATE") && !upper.contains("VALUE=DATE-TIME")
                || valueOf(line).length() == 8;
    }

    private static LocalDate day(String value) {
        try {
            return LocalDate.parse(value.substring(0, Math.min(8, value.length())), DAY);
        } catch (DateTimeParseException | StringIndexOutOfBoundsException e) {
            return null;
        }
    }

    /**
     * A moment: {@code …Z} is UTC, a {@code TZID} parameter names a zone, and
     * a bare local time is read in the server's — which is the best anyone can
     * do without the calendar's own VTIMEZONE.
     */
    private static Instant instant(String line) {
        String value = valueOf(line);
        try {
            if (value.endsWith("Z")) {
                return LocalDateTime.parse(value.substring(0, value.length() - 1), LOCAL).toInstant(ZoneOffset.UTC);
            }
            ZoneId zone = zoneOf(line);
            return LocalDateTime.parse(value, LOCAL).atZone(zone).toInstant();
        } catch (DateTimeParseException e) {
            LocalDate day = day(value);
            return day == null ? null : day.atStartOfDay(ZoneId.systemDefault()).toInstant();
        }
    }

    private static ZoneId zoneOf(String line) {
        int at = line.toUpperCase(Locale.ROOT).indexOf("TZID=");
        if (at < 0) return ZoneId.systemDefault();
        String rest = line.substring(at + 5);
        int end = rest.indexOf(':');
        int semicolon = rest.indexOf(';');
        if (semicolon >= 0 && (end < 0 || semicolon < end)) end = semicolon;
        String name = (end < 0 ? rest : rest.substring(0, end)).replace("\"", "").strip();
        try {
            return ZoneId.of(name);
        } catch (RuntimeException e) {
            return ZoneId.systemDefault();
        }
    }

    /** The address of an {@code ATTENDEE} or {@code ORGANIZER} line. */
    private static String address(String line) {
        if (line == null) return null;
        String value = valueOf(line);
        String address = value.toLowerCase(Locale.ROOT).startsWith("mailto:") ? value.substring(7) : value;
        return address.isBlank() ? null : address.strip();
    }
}
