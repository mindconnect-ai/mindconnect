package ai.mindconnect.office.tools;



import ai.mindconnect.agent.UserId;
import ai.mindconnect.agent.tool.TimeZones;
import ai.mindconnect.agent.tool.Tool;
import ai.mindconnect.calendar.CalendarAccounts;
import ai.mindconnect.calendar.CalendarEvent;
import ai.mindconnect.calendar.CalendarStore;
import ai.mindconnect.calendar.CalendarStoreException;
import ai.mindconnect.calendar.ConnectedCalendar;
import ai.mindconnect.calendar.EventDraft;
import ai.mindconnect.calendar.EventTime;
import ai.mindconnect.calendar.UserCalendar;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static ai.mindconnect.office.tools.OfficeTool.*;

/**
 * The calendar tools: every calendar of every connected account, named by
 * account and calendar id — "all" for the agenda across them.
 */
final class CalendarTools {

    static final String CALENDARS = "calendar_calendars";
    static final String EVENTS = "calendar_events";
    static final String READ = "calendar_read";
    static final String CREATE = "calendar_create";
    static final String UPDATE = "calendar_update";
    static final String DELETE = "calendar_delete";

    static final Set<String> NAMES = Set.of(CALENDARS, EVENTS, READ, CREATE, UPDATE, DELETE);

    private final CalendarAccounts calendars;
    /** The zone a time without an offset is read in, and times are shown in: the calling user's, asked per call. */
    private final TimeZones zones;

    /** One zone for everyone — for a host without users, and for tests. */
    CalendarTools(CalendarAccounts calendars, ZoneId zone) {
        this(calendars, TimeZones.fixed(zone));
    }

    CalendarTools(CalendarAccounts calendars, TimeZones zones) {
        this.calendars = calendars;
        this.zones = zones;
    }

    Optional<Tool> create(String name, UserId user) {
        Accounts<ConnectedCalendar> accounts = new Accounts<>(calendars.of(user), ConnectedCalendar::id,
                ConnectedCalendar::key, ConnectedCalendar::describe, ConnectedCalendar::usable);
        return Optional.ofNullable(switch (name) {
            case CALENDARS -> calendarList(user, accounts);
            case EVENTS -> events(user, accounts);
            case READ -> read(user, accounts);
            case CREATE -> createEvent(user, accounts);
            case UPDATE -> updateEvent(user, accounts);
            case DELETE -> deleteEvent(user, accounts);
            default -> null;
        });
    }

    private static Map<String, Object> account(Accounts<?> accounts, boolean withAll) {
        return oneOf(accounts.choices(withAll), withAll
                ? "Which account, as provider.key — or \"all\". Optional when there is only one."
                : "Which account, as provider.key. Optional when there is only one.");
    }

    private Tool calendarList(UserId user, Accounts<ConnectedCalendar> accounts) {
        return new OfficeTool(CALENDARS,
                "List the user's calendars with their ids, which one is the main one and which can be written to.",
                object(props("account", account(accounts, true))),
                args -> {
                    StringBuilder out = new StringBuilder();
                    for (ConnectedCalendar account : accounts.pick(args, true)) {
                        out.append("Calendars of ").append(account.id()).append(" (").append(account.describe()).append("):\n");
                        try (CalendarStore store = calendars.open(user, account.id())) {
                            for (UserCalendar c : store.calendars()) {
                                out.append("- ").append(c.id()).append(" — ").append(c.name())
                                        .append(c.primary() ? " (main)" : "")
                                        .append(c.writable() ? "" : " (read only)").append('\n');
                            }
                        } catch (CalendarStoreException e) {
                            out.append("  (did not answer: ").append(e.getMessage()).append(")\n");
                        }
                    }
                    return out.toString();
                });
    }

    private Tool events(UserId user, Accounts<ConnectedCalendar> accounts) {
        return new OfficeTool(EVENTS,
                "List appointments in a time window, earliest first — every calendar of the account, or of "
                        + "all accounts with \"all\", unless a calendar is named.",
                object(props(
                        "account", account(accounts, true),
                        "calendar", string("One calendar id from " + CALENDARS + "; every calendar when omitted."),
                        "from", string("Start of the window, as 2026-09-22 or 2026-09-22T14:00 in the user's time zone; "
                                + "today when omitted."),
                        "to", string("End of the window; seven days after the start when omitted."),
                        "limit", integer("At most this many (default 50, at most 200)."))),
                args -> {
                    ZoneId zone = zones.zoneOf(user);
                    Instant from = Optional.ofNullable(instant(args, "from", zone))
                            .orElse(LocalDate.now(zone).atStartOfDay(zone).toInstant());
                    Instant to = Optional.ofNullable(instant(args, "to", zone)).orElse(from.plus(7, ChronoUnit.DAYS));
                    if (!to.isAfter(from)) throw new Refused("\"to\" has to be after \"from\".");
                    int limit = Math.max(1, number(args, "limit", 50, 200));
                    String only = str(args, "calendar");
                    List<Row> rows = new ArrayList<>();
                    List<String> silent = new ArrayList<>();
                    for (ConnectedCalendar account : accounts.pick(args, true)) {
                        try (CalendarStore store = calendars.open(user, account.id())) {
                            for (UserCalendar c : store.calendars()) {
                                if (only != null && !c.id().equals(only)) continue;
                                for (CalendarEvent e : store.events(c.id(), from, to, limit)) {
                                    if (!e.cancelled()) rows.add(new Row(account.id(), c.name(), e));
                                }
                            }
                        } catch (CalendarStoreException e) {
                            silent.add(account.id());
                        }
                    }
                    rows.sort(Comparator.comparing(r -> r.event().when().startsIn(zone)));
                    StringBuilder out = new StringBuilder();
                    out.append(rows.isEmpty() ? "Nothing" : rows.size() + " appointments")
                            .append(" between ").append(when(from, zone)).append(" and ").append(when(to, zone))
                            .append(rows.isEmpty() ? ".\n" : ":\n\n");
                    for (Row row : rows.stream().limit(limit).toList()) out.append(line(row, zone));
                    if (!silent.isEmpty()) out.append("\n(Did not answer: ").append(String.join(", ", silent)).append(")\n");
                    return out.toString();
                });
    }

    private Tool read(UserId user, Accounts<ConnectedCalendar> accounts) {
        return new OfficeTool(READ,
                "Read one appointment in full: time, place, organiser, attendees and notes.",
                object(props(
                        "account", account(accounts, false),
                        "calendar", string("The calendar id it was listed in."),
                        "id", string("The appointment id from " + EVENTS + ".")), "calendar", "id"),
                args -> {
                    ConnectedCalendar account = accounts.one(args);
                    try (CalendarStore store = calendars.open(user, account.id())) {
                        CalendarEvent e = store.read(required(args, "calendar"), required(args, "id"));
                        StringBuilder out = new StringBuilder(line(new Row(account.id(), e.calendarId(), e),
                                zones.zoneOf(user)));
                        if (e.organiser() != null) out.append("  organiser: ").append(e.organiser()).append('\n');
                        if (e.attendees() != null && !e.attendees().isEmpty()) {
                            out.append("  attendees: ").append(String.join(", ", e.attendees())).append('\n');
                        }
                        if (e.notes() != null && !e.notes().isBlank()) out.append('\n').append(cut(e.notes(), 8000));
                        return out.toString();
                    }
                });
    }

    private Tool createEvent(UserId user, Accounts<ConnectedCalendar> accounts) {
        return new OfficeTool(CREATE,
                "Put an appointment into one of the user's calendars. Invitations go out to attendees the way "
                        + "the provider sends them.",
                object(props(
                        "account", account(accounts, false),
                        "calendar", string("The calendar id; the main one when omitted."),
                        "title", string("What it is."),
                        "start", string("When it starts, as 2026-09-22T14:00 in the user's time zone — or 2026-09-22 "
                                + "for a whole day."),
                        "end", string("When it ends; an hour after the start (or the same day) when omitted."),
                        "location", string("Where."),
                        "notes", string("A description."),
                        "attendees", strings("E-mail addresses to invite.")), "title", "start"),
                args -> {
                    ZoneId zone = zones.zoneOf(user);
                    ConnectedCalendar account = accounts.one(args);
                    String start = required(args, "start");
                    EventTime when;
                    if (start.length() == 10) {
                        LocalDate first = date(args, "start");
                        LocalDate last = Optional.ofNullable(date(args, "end")).orElse(first);
                        when = EventTime.on(first, last);
                    } else {
                        Instant begins = instant(args, "start", zone);
                        Instant ends = Optional.ofNullable(instant(args, "end", zone)).orElse(begins.plus(1, ChronoUnit.HOURS));
                        if (!ends.isAfter(begins)) throw new Refused("\"end\" has to be after \"start\".");
                        when = EventTime.at(begins, ends);
                    }
                    try (CalendarStore store = calendars.open(user, account.id())) {
                        if (!store.canCreate()) throw new Refused(account.id() + " cannot create appointments.");
                        String calendar = Optional.ofNullable(str(args, "calendar")).orElseGet(() ->
                                store.calendars().stream().filter(c -> c.primary() && c.writable()).findFirst()
                                        .or(() -> store.calendars().stream().filter(UserCalendar::writable).findFirst())
                                        .map(UserCalendar::id)
                                        .orElseThrow(() -> new Refused(account.id() + " has no calendar to write to.")));
                        String id = store.create(new EventDraft(calendar, required(args, "title"), when,
                                str(args, "location"), str(args, "notes"), list(args, "attendees")));
                        return "Created \"" + str(args, "title") + "\" in " + account.id() + " (id " + id + ").";
                    }
                });
    }

    private Tool updateEvent(UserId user, Accounts<ConnectedCalendar> accounts) {
        return new OfficeTool(UPDATE,
                "Change an appointment: move it, rename it, change the place, the notes or who is invited. "
                        + "What is not given stays as it is; a new start without an end keeps the length. "
                        + "Attendees are told the way the provider tells them.",
                object(props(
                        "account", account(accounts, false),
                        "calendar", string("The calendar id it is in."),
                        "id", string("The appointment id from " + EVENTS + "."),
                        "title", string("A new title."),
                        "start", string("A new start, as 2026-09-22T14:00 in the user's time zone — or 2026-09-22 "
                                + "for a whole day."),
                        "end", string("A new end."),
                        "location", string("A new place; an empty string clears it."),
                        "notes", string("New notes."),
                        "attendees", strings("The attendees from now on — the whole list.")),
                        "calendar", "id"),
                args -> {
                    ZoneId zone = zones.zoneOf(user);
                    ConnectedCalendar account = accounts.one(args);
                    String calendar = required(args, "calendar");
                    try (CalendarStore store = calendars.open(user, account.id())) {
                        CalendarEvent was = store.read(calendar, required(args, "id"));
                        EventTime when = was.when();
                        String start = str(args, "start");
                        if (start != null && start.length() == 10) {
                            LocalDate first = date(args, "start");
                            LocalDate last = Optional.ofNullable(date(args, "end")).orElse(first);
                            when = EventTime.on(first, last);
                        } else if (start != null) {
                            Instant begins = instant(args, "start", zone);
                            java.time.Duration length = when.allDay() || when.start() == null || when.end() == null
                                    ? java.time.Duration.ofHours(1)
                                    : java.time.Duration.between(when.start(), when.end());
                            Instant ends = Optional.ofNullable(instant(args, "end", zone)).orElse(begins.plus(length));
                            if (!ends.isAfter(begins)) throw new Refused("\"end\" has to be after \"start\".");
                            when = EventTime.at(begins, ends);
                        } else if (str(args, "end") != null && !when.allDay()) {
                            Instant ends = instant(args, "end", zone);
                            if (!ends.isAfter(when.start())) throw new Refused("\"end\" has to be after the start.");
                            when = EventTime.at(when.start(), ends);
                        }
                        EventDraft draft = new EventDraft(calendar,
                                Optional.ofNullable(str(args, "title")).orElse(was.title()), when,
                                args.containsKey("location") ? str(args, "location") : was.location(),
                                args.containsKey("notes") ? str(args, "notes") : was.notes(),
                                args.containsKey("attendees") ? list(args, "attendees")
                                        : was.attendees() == null ? List.of() : was.attendees());
                        store.update(calendar, was.id(), draft);
                        return "Changed \"" + draft.title() + "\".";
                    }
                });
    }

    private Tool deleteEvent(UserId user, Accounts<ConnectedCalendar> accounts) {
        return new OfficeTool(DELETE,
                "Delete an appointment. For a meeting the user organised, the attendees are told it is cancelled.",
                object(props(
                        "account", account(accounts, false),
                        "calendar", string("The calendar id it is in."),
                        "id", string("The appointment id from " + EVENTS + ".")), "calendar", "id"),
                args -> {
                    ConnectedCalendar account = accounts.one(args);
                    try (CalendarStore store = calendars.open(user, account.id())) {
                        CalendarEvent was = store.read(required(args, "calendar"), required(args, "id"));
                        store.delete(required(args, "calendar"), was.id());
                        return "Deleted \"" + was.title() + "\".";
                    }
                });
    }

    private record Row(String account, String calendar, CalendarEvent event) { }

    private static String line(Row row, ZoneId zone) {
        CalendarEvent e = row.event();
        EventTime t = e.when();
        String time = t.allDay()
                ? t.startDate() + (t.endDate() != null && !t.endDate().equals(t.startDate()) ? " – " + t.endDate() : "")
                + " (all day)"
                : when(t.start(), zone) + " – " + when(t.end(), zone).substring(11);
        StringBuilder out = new StringBuilder("- ").append(time).append("  ").append(e.title()).append('\n')
                .append("  id ").append(e.id()).append(" · account ").append(row.account())
                .append(" · calendar ").append(e.calendarId()).append('\n');
        if (e.hasLocation()) out.append("  where: ").append(e.location()).append('\n');
        return out.toString();
    }
}
