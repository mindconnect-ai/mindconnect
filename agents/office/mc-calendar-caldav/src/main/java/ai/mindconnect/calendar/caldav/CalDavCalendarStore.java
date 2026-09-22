package ai.mindconnect.calendar.caldav;

import ai.mindconnect.calendar.CalendarEvent;
import ai.mindconnect.calendar.CalendarStore;
import ai.mindconnect.calendar.CalendarStoreException;
import ai.mindconnect.calendar.EventDraft;
import ai.mindconnect.calendar.UserCalendar;

import java.net.URI;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * A CalDAV account as {@link CalendarStore}.
 *
 * <p><b>A calendar's id is its URL.</b> CalDAV addresses everything by URL:
 * the account's own is either one calendar or the home that holds several,
 * and an appointment is a file in one of them. So {@link UserCalendar#id()} is
 * the calendar's collection URL and an appointment's id is its {@code UID} —
 * the two things the server needs, and both stable.
 *
 * <p>What this does not do: recurrence rules (a recurring appointment is read
 * as the occurrences the server returns for the period, and changing one of
 * them changes the file), free/busy, invitations beyond writing the attendees
 * into the event, and calendars shared by somebody else unless the account's
 * home lists them.
 */
public final class CalDavCalendarStore implements CalendarStore {

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'", Locale.ROOT).withZone(ZoneOffset.UTC);

    private final CalDavClient dav;
    private final CalDavAccount account;
    private final ZoneId zone;

    /** Calendars are asked for once per store; the screens ask several times per request. */
    private List<UserCalendar> calendars;

    public CalDavCalendarStore(CalDavClient dav, CalDavAccount account) {
        this(dav, account, ZoneId.systemDefault());
    }

    public CalDavCalendarStore(CalDavClient dav, CalDavAccount account, ZoneId zone) {
        this.dav = Objects.requireNonNull(dav, "dav");
        this.account = Objects.requireNonNull(account, "account");
        this.zone = Objects.requireNonNull(zone, "zone");
    }

    /**
     * The calendars under the account's address — or the address itself when
     * it is one. The first is the one a call that names none writes to.
     */
    @Override
    public List<UserCalendar> calendars() {
        if (calendars != null) return calendars;
        String xml = call(() -> dav.propfind(account, account.url(), 1, CalDavClient.Bodies.calendars()));
        List<UserCalendar> found = new ArrayList<>();
        for (Map.Entry<String, String> response : CalDavClient.responses(xml, "prop").entrySet()) {
            String prop = response.getValue();
            if (!CalDavClient.has(prop, "calendar")) continue;
            // A collection that holds only todos is not a calendar for us.
            List<String> components = CalDavClient.between(prop, "supported-calendar-component-set");
            if (!components.isEmpty() && !CalDavClient.has(components.get(0), "comp")) continue;
            if (!components.isEmpty() && !components.get(0).toUpperCase(Locale.ROOT).contains("VEVENT")) continue;
            String href = response.getKey();
            String name = first(CalDavClient.between(prop, "displayname"));
            boolean writable = !prop.toLowerCase(Locale.ROOT).contains("read-only");
            found.add(new UserCalendar(resolve(href).toString(),
                    name == null || name.isBlank() ? lastSegment(href) : name.strip(),
                    first(CalDavClient.between(prop, "calendar-color")), false, writable));
        }
        if (found.isEmpty()) {
            // The address is the calendar itself, or the server answers no
            // properties for it; either way there is one calendar here.
            found.add(new UserCalendar(account.url().toString(), lastSegment(account.url().getPath()), null,
                    true, true));
        } else {
            found.sort(Comparator.comparing(UserCalendar::name, String.CASE_INSENSITIVE_ORDER));
            UserCalendar main = found.get(0);
            found.set(0, new UserCalendar(main.id(), main.name(), main.colour(), true, main.writable()));
        }
        calendars = List.copyOf(found);
        return calendars;
    }

    @Override
    public List<CalendarEvent> events(String calendarId, Instant from, Instant to, int limit) {
        URI calendar = calendar(calendarId);
        String xml = call(() -> dav.report(account, calendar,
                CalDavClient.Bodies.eventsBetween(STAMP.format(from), STAMP.format(to))));
        List<CalendarEvent> events = new ArrayList<>();
        for (Map.Entry<String, String> response : CalDavClient.responses(xml, "calendar-data").entrySet()) {
            events.addAll(ICalendar.events(response.getValue(), calendar.toString(), response.getKey()));
        }
        events.sort(Comparator.comparing(e -> e.when().startsIn(zone)));
        return limit > 0 && events.size() > limit ? List.copyOf(events.subList(0, limit)) : List.copyOf(events);
    }

    @Override
    public CalendarEvent read(String calendarId, String eventId) {
        URI calendar = calendar(calendarId);
        String ical = call(() -> dav.get(account, file(calendar, eventId)));
        List<CalendarEvent> found = ICalendar.events(ical, calendar.toString(), eventId);
        if (found.isEmpty()) {
            throw new CalendarStoreException("There is no appointment " + eventId + " in that calendar.");
        }
        return found.get(0);
    }

    @Override
    public boolean canCreate() {
        return calendars().stream().anyMatch(UserCalendar::writable);
    }

    @Override
    public String create(EventDraft draft) {
        String uid = UUID.randomUUID() + "@mindconnect";
        URI calendar = calendar(draft.calendarId());
        call(() -> {
            dav.put(account, file(calendar, uid), ICalendar.write(draft, uid, zone));
            return "";
        });
        return uid;
    }

    /**
     * Writes the appointment again, whole. CalDAV has no partial write: a PUT
     * replaces the file, which is why the caller reads the appointment first
     * and hands back everything it means to keep.
     */
    @Override
    public void update(String calendarId, String eventId, EventDraft draft) {
        URI calendar = calendar(calendarId);
        call(() -> {
            dav.put(account, file(calendar, eventId), ICalendar.write(draft, eventId, zone));
            return "";
        });
    }

    @Override
    public void delete(String calendarId, String eventId) {
        URI calendar = calendar(calendarId);
        call(() -> {
            dav.delete(account, file(calendar, eventId));
            return "";
        });
    }

    @Override
    public void close() {
        // Nothing is held open.
    }

    // ── addresses ───────────────────────────────────────────────────────────

    /** The calendar a call names, or the first one when it names none. */
    private URI calendar(String calendarId) {
        if (calendarId == null || calendarId.isBlank()) {
            List<UserCalendar> all = calendars();
            if (all.isEmpty()) throw new CalendarStoreException("This account has no calendar.");
            return URI.create(all.get(0).id());
        }
        return resolve(calendarId);
    }

    /** Where an appointment's file lives: {@code <calendar>/<uid>.ics}. */
    private static URI file(URI calendar, String eventId) {
        String base = calendar.toString();
        if (!base.endsWith("/")) base = base + "/";
        String name = eventId.endsWith(".ics") ? eventId : eventId + ".ics";
        return URI.create(base + encode(name));
    }

    /** A server answers with paths; the store keeps whole URLs. */
    private URI resolve(String href) {
        URI uri = URI.create(href.strip());
        return uri.isAbsolute() ? uri : account.url().resolve(uri);
    }

    private static String encode(String segment) {
        return java.net.URLEncoder.encode(segment, java.nio.charset.StandardCharsets.UTF_8)
                .replace("+", "%20");
    }

    private static String lastSegment(String path) {
        if (path == null || path.isBlank()) return "Calendar";
        String trimmed = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
        int slash = trimmed.lastIndexOf('/');
        String name = slash < 0 ? trimmed : trimmed.substring(slash + 1);
        return name.isBlank() ? "Calendar" : java.net.URLDecoder.decode(name,
                java.nio.charset.StandardCharsets.UTF_8);
    }

    private static String first(List<String> values) {
        return values.isEmpty() ? null : values.get(0);
    }

    /** The server's own sentence, as the calendar port's exception. */
    private static <T> T call(java.util.function.Supplier<T> request) {
        try {
            return request.get();
        } catch (CalDavException e) {
            throw new CalendarStoreException(e.getMessage(), e);
        }
    }
}
