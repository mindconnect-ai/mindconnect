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
import java.util.Objects;

/**
 * As much of iCalendar as an appointment is: reading a {@code VEVENT} into a
 * {@link CalendarEvent} and writing one back.
 *
 * <p><b>Not a calendar library.</b> Recurrence rules, alarms, time-zone
 * definitions and journals are left where they are: a recurring appointment is
 * read as the occurrences the server expanded for a period, and changing an
 * appointment {@linkplain #patch patches} the file the server holds — only
 * the lines the {@link EventDraft} actually changes are rewritten, so a rule,
 * an exception date, an alarm or a line this class has never heard of
 * survives the change. Only a new appointment is written from scratch. The
 * reader is deliberately forgiving; every provider writes this format a
 * little differently.
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

    /**
     * The appointment a resource is about: the series itself rather than one
     * of its changed occurrences (a {@code VEVENT} with a
     * {@code RECURRENCE-ID}), or null when it holds none.
     */
    static CalendarEvent master(String ical, String calendarId, String id) {
        CalendarEvent first = null;
        for (String block : blocks(unfold(ical))) {
            CalendarEvent event = event(block, calendarId, id);
            if (event == null) continue;
            if (value(block, "RECURRENCE-ID") == null) return event;
            if (first == null) first = event;
        }
        return first;
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

    /**
     * {@code DTSTART}/{@code DTEND} as a period — a day, or a time and its end.
     * An appointment may say how long it is instead of when it ends
     * ({@code DURATION}, RFC 5545 3.8.2.5); only when it says neither does it
     * last a day, or an hour.
     */
    private static EventTime when(String block) {
        String start = firstLine(block, "DTSTART");
        if (start == null) return null;
        String end = firstLine(block, "DTEND");
        Length length = end == null ? length(value(block, "DURATION")) : null;
        if (isDate(start)) {
            LocalDate first = day(valueOf(start));
            if (first == null) return null;
            // iCalendar's DTEND of an all-day event is the morning after it.
            LocalDate afterLast = end != null ? day(valueOf(end))
                    : length != null ? first.plusDays(length.days() + length.seconds() / 86_400) : null;
            LocalDate last = afterLast == null || !afterLast.isAfter(first) ? first : afterLast.minusDays(1);
            return EventTime.on(first, last);
        }
        Instant from = instant(start);
        if (from == null) return null;
        Instant to = end != null ? instant(end)
                : length != null ? from.atZone(zoneOfValue(start)).plusDays(length.days())
                        .plusSeconds(length.seconds()).toInstant()
                : null;
        return EventTime.at(from, to == null || !to.isAfter(from) ? from.plusSeconds(3600) : to);
    }

    /**
     * An iCalendar duration — {@code PT30M}, {@code P3D}, {@code P1W},
     * {@code P1DT2H30M} — as days and seconds, or null when it is none. Days
     * stay days rather than 24 hours: the format counts a day across a clock
     * change as the same time on the next date.
     */
    static Length length(String value) {
        if (value == null) return null;
        java.util.regex.Matcher m = DURATION.matcher(value.strip());
        if (!m.matches()) return null;
        if (m.group(2) == null && m.group(3) == null && m.group(4) == null
                && m.group(5) == null && m.group(6) == null) {
            return null;
        }
        long days = m.group(2) != null ? 7 * Long.parseLong(m.group(2)) : number(m.group(3));
        long seconds = number(m.group(4)) * 3600 + number(m.group(5)) * 60 + number(m.group(6));
        return "-".equals(m.group(1)) ? new Length(-days, -seconds) : new Length(days, seconds);
    }

    /** A duration as the format counts it: whole days, and the time on top of them. */
    record Length(long days, long seconds) { }

    private static final java.util.regex.Pattern DURATION = java.util.regex.Pattern.compile(
            "([+-])?P(?:(\\d+)W|(?:(\\d+)D)?(?:T(?:(\\d+)H)?(?:(\\d+)M)?(?:(\\d+)S)?)?)",
            java.util.regex.Pattern.CASE_INSENSITIVE);

    private static long number(String digits) {
        return digits == null ? 0 : Long.parseLong(digits);
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
        times(out, draft.when());
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

    /**
     * {@code original} — the resource as the server holds it — with the
     * changes {@code draft} makes to its appointment, and nothing else.
     *
     * <p>A PUT replaces the whole file, and a draft knows only title, time,
     * place, notes and attendees. Writing the file from the draft would drop
     * everything else: the recurrence rule and its exception dates (a weekly
     * meeting would become a single one), changed occurrences, alarms,
     * time-zone definitions, an attendee's answer. So the series' own
     * {@code VEVENT} is edited line by line: a property the draft leaves as
     * it was keeps its line — parameters and all — a changed one is written
     * anew, an attendee still invited keeps their {@code PARTSTAT}.
     * {@code DTSTAMP}, {@code LAST-MODIFIED} and {@code SEQUENCE} say that it
     * changed.
     *
     * @throws CalDavException when the draft moves a recurring appointment:
     *         the draft cannot say whether it means the one occurrence or the
     *         whole series, and moving the series' start would drop every
     *         occurrence before the new one
     */
    static String patch(String original, EventDraft draft) {
        List<String> raw = logicalLines(original);
        int begin = -1;
        int end = -1;
        int fallbackBegin = -1;
        int fallbackEnd = -1;
        for (int i = 0; i < raw.size(); i++) {
            if (!unfoldedName(raw.get(i)).equals("BEGIN") || !valueOf(unfold(raw.get(i))).equalsIgnoreCase("VEVENT")) {
                continue;
            }
            int close = closing(raw, i);
            if (close < 0) break;
            if (fallbackBegin < 0) {
                fallbackBegin = i;
                fallbackEnd = close;
            }
            if (topLevel(raw, i, close).stream().noneMatch(n -> unfoldedName(raw.get(n)).equals("RECURRENCE-ID"))) {
                begin = i;
                end = close;
                break;
            }
            i = close;
        }
        if (begin < 0) {
            begin = fallbackBegin;
            end = fallbackEnd;
        }
        if (begin < 0) throw new CalDavException("The appointment on the server has no VEVENT to change.");

        List<Integer> own = topLevel(raw, begin, end);
        StringBuilder flat = new StringBuilder();
        for (int n : own) flat.append(unfold(raw.get(n))).append('\n');
        CalendarEvent was = event(flat.toString(), "", "");
        if (was == null) throw new CalDavException("The appointment on the server has no start to change.");
        boolean recurring = own.stream().map(n -> unfoldedName(raw.get(n)))
                .anyMatch(n -> n.equals("RRULE") || n.equals("RDATE"));

        boolean moved = !draft.when().equals(was.when());
        if (moved && recurring) {
            throw new CalDavException("\"" + was.title() + "\" is a recurring appointment. Moving it here would "
                    + "move the whole series and drop its earlier occurrences — change its time in the "
                    + "calendar app instead. Title, place, notes and attendees can be changed here.");
        }
        boolean retitled = !Objects.equals(blankless(draft.title()), blankless(was.title()));
        boolean relocated = !Objects.equals(blankless(draft.location()), blankless(was.location()));
        boolean renoted = !Objects.equals(blankless(draft.notes()), blankless(was.notes()));
        java.util.Set<String> invited = new java.util.LinkedHashSet<>();
        for (String a : draft.attendees()) {
            if (a != null && !a.isBlank()) invited.add(a.strip().toLowerCase(Locale.ROOT));
        }
        java.util.Set<String> before = new java.util.LinkedHashSet<>();
        for (String a : was.attendees()) before.add(a.toLowerCase(Locale.ROOT));
        boolean reinvited = !invited.equals(before);

        long sequence = 0;
        java.util.Set<Integer> dropped = new java.util.HashSet<>();
        for (int n : own) {
            String line = raw.get(n);
            String name = unfoldedName(line);
            switch (name) {
                case "DTSTAMP", "LAST-MODIFIED" -> dropped.add(n);
                case "SEQUENCE" -> {
                    dropped.add(n);
                    try {
                        sequence = Long.parseLong(valueOf(unfold(line)));
                    } catch (NumberFormatException e) {
                        sequence = 0;
                    }
                }
                case "DTSTART", "DTEND", "DURATION" -> {
                    if (moved) dropped.add(n);
                }
                case "SUMMARY" -> {
                    if (retitled) dropped.add(n);
                }
                case "LOCATION" -> {
                    if (relocated) dropped.add(n);
                }
                case "DESCRIPTION" -> {
                    if (renoted) dropped.add(n);
                }
                case "ATTENDEE" -> {
                    String address = address(unfold(line));
                    if (reinvited && (address == null || !invited.contains(address.toLowerCase(Locale.ROOT)))) {
                        dropped.add(n);
                    }
                }
                default -> { }
            }
        }

        StringBuilder added = new StringBuilder();
        String now = STAMP.format(Instant.now());
        added.append("DTSTAMP:").append(now).append("\r\n")
                .append("LAST-MODIFIED:").append(now).append("\r\n")
                .append("SEQUENCE:").append(sequence + 1).append("\r\n");
        if (moved) times(added, draft.when());
        if (retitled) line(added, "SUMMARY", draft.title());
        if (relocated) line(added, "LOCATION", draft.location());
        if (renoted) line(added, "DESCRIPTION", draft.notes());
        if (reinvited) {
            for (String address : invited) {
                if (!before.contains(address)) {
                    added.append("ATTENDEE;ROLE=REQ-PARTICIPANT:mailto:").append(address).append("\r\n");
                }
            }
        }

        // Properties come before the alarms inside a VEVENT (RFC 5545, 3.6.1).
        int alarms = end;
        for (int i = begin + 1; i < end; i++) {
            if (unfoldedName(raw.get(i)).equals("BEGIN")) {
                alarms = i;
                break;
            }
        }
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < raw.size(); i++) {
            if (i == alarms) out.append(added);
            if (!dropped.contains(i)) out.append(raw.get(i)).append("\r\n");
        }
        return out.toString();
    }

    private static void times(StringBuilder out, EventTime when) {
        if (when.allDay()) {
            out.append("DTSTART;VALUE=DATE:").append(DAY.format(when.startDate())).append("\r\n")
                    .append("DTEND;VALUE=DATE:").append(DAY.format(when.endDate().plusDays(1))).append("\r\n");
        } else {
            out.append("DTSTART:").append(STAMP.format(when.start())).append("\r\n")
                    .append("DTEND:").append(STAMP.format(when.end())).append("\r\n");
        }
    }

    private static String blankless(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    /** The content lines of {@code ical}, each still folded the way the server wrote it. */
    private static List<String> logicalLines(String ical) {
        List<String> lines = new ArrayList<>();
        for (String line : (ical == null ? "" : ical).replace("\r\n", "\n").split("\n")) {
            if (!lines.isEmpty() && !line.isEmpty() && (line.charAt(0) == ' ' || line.charAt(0) == '\t')) {
                lines.set(lines.size() - 1, lines.get(lines.size() - 1) + "\r\n" + line);
            } else if (!line.isBlank()) {
                lines.add(line);
            }
        }
        return lines;
    }

    /** The {@code END:VEVENT} that closes the {@code BEGIN:VEVENT} at {@code begin}, or -1. */
    private static int closing(List<String> raw, int begin) {
        int depth = 0;
        for (int i = begin; i < raw.size(); i++) {
            String name = unfoldedName(raw.get(i));
            if (name.equals("BEGIN")) depth++;
            if (name.equals("END") && --depth == 0) return i;
        }
        return -1;
    }

    /** Which lines between {@code begin} and {@code end} belong to the VEVENT itself, not to an alarm in it. */
    private static List<Integer> topLevel(List<String> raw, int begin, int end) {
        List<Integer> own = new ArrayList<>();
        int depth = 0;
        for (int i = begin + 1; i < end; i++) {
            String name = unfoldedName(raw.get(i));
            if (name.equals("BEGIN")) depth++;
            else if (name.equals("END")) depth--;
            else if (depth == 0) own.add(i);
        }
        return own;
    }

    /** A content line's name, upper case — what is before the first {@code ;} or {@code :}. */
    private static String unfoldedName(String line) {
        String flat = unfold(line).strip();
        int end = flat.length();
        for (int i = 0; i < flat.length(); i++) {
            char c = flat.charAt(i);
            if (c == ';' || c == ':') {
                end = i;
                break;
            }
        }
        return flat.substring(0, end).toUpperCase(Locale.ROOT);
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

    /** The zone a date-time line is in: UTC for {@code …Z}, else its {@code TZID} or the server's. */
    private static ZoneId zoneOfValue(String line) {
        return valueOf(line).endsWith("Z") ? ZoneOffset.UTC : zoneOf(line);
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
