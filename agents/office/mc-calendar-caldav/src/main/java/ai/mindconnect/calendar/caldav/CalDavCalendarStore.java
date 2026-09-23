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
import java.util.HashMap;
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
 * the two things the server needs, and both stable. The file an appointment
 * lives in is usually {@code <calendar>/<UID>.ics} — it is where this store
 * writes new ones — but a server or another client may have named it
 * otherwise, so when that address is empty the store asks the calendar which
 * file holds the UID.
 *
 * <p><b>Every request goes to the account's own server.</b> Each one carries
 * the account's password, and a calendar id arrives from the model — which
 * reads mail and web pages somebody else wrote. An id is therefore only
 * followed when it is one of the account's {@link #calendars()} or lies under
 * the account's address on the same scheme, host and port; anything else is
 * refused before a byte is sent.
 *
 * <p>What this does not do: recurrence rules beyond keeping them (a recurring
 * appointment is read as the occurrences the server expands for the period,
 * changing its title, place, notes or attendees changes the series, and moving
 * it is refused), free/busy, invitations beyond writing the attendees into the
 * event, and calendars shared by somebody else unless the account's home lists
 * them.
 */
public final class CalDavCalendarStore implements CalendarStore {

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'", Locale.ROOT).withZone(ZoneOffset.UTC);

    private final CalDavClient dav;
    private final CalDavAccount account;
    private final ZoneId zone;

    /** Calendars are asked for once per store; the screens ask several times per request. */
    private List<UserCalendar> calendars;

    /**
     * The files read in this store, by calendar and appointment id. A change
     * reads the appointment first (the tools do, to fill in what the model
     * left out) and then writes it: the write goes to the file that was read
     * and only if it is still the version that was read.
     */
    private final Map<String, CalDavClient.Resource> read = new HashMap<>();

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
        CalDavClient.Resource file = call(() -> locate(calendar, eventId));
        CalendarEvent found = ICalendar.master(file.body(), calendar.toString(), eventId);
        if (found == null) throw missing(eventId);
        return found;
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
     * Writes the appointment again. CalDAV has no partial write: a PUT
     * replaces the file, so the file as the server holds it is patched with
     * what the draft changes ({@link ICalendar#patch}) — a recurrence rule, an
     * alarm or an attendee's answer is kept — and written back with
     * {@code If-Match}, so that a change somebody made in between fails
     * instead of being overwritten.
     */
    @Override
    public void update(String calendarId, String eventId, EventDraft draft) {
        URI calendar = calendar(calendarId);
        call(() -> {
            CalDavClient.Resource file = locate(calendar, eventId);
            dav.put(account, file.url(), ICalendar.patch(file.body(), draft), file.etag());
            read.remove(key(calendar, eventId));
            return "";
        });
    }

    @Override
    public void delete(String calendarId, String eventId) {
        URI calendar = calendar(calendarId);
        call(() -> {
            CalDavClient.Resource file = locate(calendar, eventId);
            dav.delete(account, file.url(), file.etag());
            read.remove(key(calendar, eventId));
            return "";
        });
    }

    @Override
    public void close() {
        // Nothing is held open.
    }

    // ── addresses ───────────────────────────────────────────────────────────

    /**
     * The calendar a call names, or the first one when it names none.
     *
     * <p>The id comes from the model, and whatever URL it is, the request to
     * it carries the account's password. So it has to be a calendar the
     * server listed, or an address under the account's own on the same
     * server; a prompt that talks the model into
     * {@code https://attacker.example/} gets a refusal, not the password.
     */
    private URI calendar(String calendarId) {
        if (calendarId == null || calendarId.isBlank()) {
            List<UserCalendar> all = calendars();
            if (all.isEmpty()) throw new CalendarStoreException("This account has no calendar.");
            return URI.create(all.get(0).id());
        }
        URI uri;
        try {
            uri = resolve(calendarId);
        } catch (IllegalArgumentException e) {
            throw notOurs(calendarId);
        }
        if (sameServer(uri, account.url()) && under(uri, account.url())) return uri;
        for (UserCalendar listed : calendars()) {
            URI known = URI.create(listed.id());
            if (known.normalize().equals(uri.normalize())) return known;
        }
        throw notOurs(calendarId);
    }

    private static CalendarStoreException notOurs(String calendarId) {
        return new CalendarStoreException("\"" + calendarId + "\" is not a calendar of this account. "
                + "Use a calendar id from the account's list of calendars.");
    }

    /**
     * The file that holds {@code eventId}, with the version it has now.
     *
     * <p>First where this store would have put it, {@code <calendar>/<uid>.ics};
     * when nothing is there — or something else is — the calendar is asked
     * which of its files holds that {@code UID}.
     */
    private CalDavClient.Resource locate(URI calendar, String eventId) {
        String key = key(calendar, eventId);
        CalDavClient.Resource known = read.get(key);
        if (known != null) return known;
        CalDavClient.Resource found = null;
        try {
            CalDavClient.Resource direct = dav.fetch(account, file(calendar, eventId));
            if (holds(direct.body(), eventId)) found = direct;
        } catch (CalDavException e) {
            if (e.status() != 404) throw e;
        }
        if (found == null) found = byUid(calendar, eventId);
        if (found == null) throw missing(eventId);
        read.put(key, found);
        return found;
    }

    /** The file whose {@code VEVENT} carries this {@code UID}, asked of the calendar; null when none does. */
    private CalDavClient.Resource byUid(URI calendar, String uid) {
        String xml;
        try {
            xml = dav.report(account, calendar, CalDavClient.Bodies.eventByUid(uid));
        } catch (CalDavException e) {
            // A server that cannot filter by UID has no such file to offer either.
            if (e.status() == 0 || e.status() == 401 || e.status() == 403) throw e;
            return null;
        }
        Map<String, String> etags = CalDavClient.responses(xml, "getetag");
        for (Map.Entry<String, String> response : CalDavClient.responses(xml, "calendar-data").entrySet()) {
            if (!holds(response.getValue(), uid)) continue;
            URI href = resolve(response.getKey());
            // The server's answer, but the password goes wherever it points.
            if (!sameServer(href, calendar)) continue;
            String etag = etags.get(response.getKey());
            return new CalDavClient.Resource(href, etag == null || etag.isBlank() ? null : etag.strip(),
                    response.getValue());
        }
        return null;
    }

    private static boolean holds(String ical, String eventId) {
        return ICalendar.events(ical, "", eventId).stream().anyMatch(e -> e.id().equals(eventId));
    }

    private static String key(URI calendar, String eventId) {
        return calendar + "\n" + eventId;
    }

    private static CalendarStoreException missing(String eventId) {
        return new CalendarStoreException("There is no appointment " + eventId + " in that calendar.");
    }

    /**
     * Where an appointment's file lives: {@code <calendar>/<uid>.ics}. An
     * appointment without a {@code UID} is known by its file's path, which
     * has to lie inside the calendar.
     */
    private URI file(URI calendar, String eventId) {
        String base = calendar.toString();
        if (!base.endsWith("/")) base = base + "/";
        if (eventId.startsWith("/")) {
            URI path;
            try {
                path = calendar.resolve(eventId);
            } catch (IllegalArgumentException e) {
                throw missing(eventId);
            }
            if (!sameServer(path, calendar) || !under(path, calendar)) throw missing(eventId);
            return path;
        }
        String name = eventId.endsWith(".ics") ? eventId : eventId + ".ics";
        return URI.create(base + encode(name));
    }

    /** A server answers with paths; the store keeps whole URLs. */
    private URI resolve(String href) {
        URI uri = URI.create(href.strip());
        return uri.isAbsolute() ? uri : account.url().resolve(uri);
    }

    /** Same scheme, host and port — and no user name smuggled in front of the host. */
    static boolean sameServer(URI uri, URI home) {
        return uri.getScheme() != null && uri.getHost() != null && uri.getRawUserInfo() == null
                && uri.getScheme().equalsIgnoreCase(home.getScheme())
                && uri.getHost().equalsIgnoreCase(home.getHost())
                && port(uri) == port(home);
    }

    /** True when {@code uri}'s path is {@code home}'s or below it, after {@code ..} is taken out. */
    static boolean under(URI uri, URI home) {
        String path = uri.normalize().getPath();
        String base = home.normalize().getPath();
        if (path == null || base == null) return false;
        if (path.contains("/../") || path.endsWith("/..") || path.startsWith("..")) return false;
        String dir = base.endsWith("/") ? base : base + "/";
        return path.equals(base) || (path + "/").equals(dir) || path.startsWith(dir);
    }

    private static int port(URI uri) {
        if (uri.getPort() >= 0) return uri.getPort();
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
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
