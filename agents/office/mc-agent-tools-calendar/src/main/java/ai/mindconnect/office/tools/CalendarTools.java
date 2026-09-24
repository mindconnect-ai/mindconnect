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
import ai.mindconnect.calendar.ReminderSupport;
import ai.mindconnect.calendar.Reminders;
import ai.mindconnect.calendar.UserCalendar;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.math.BigDecimal;
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
 *
 * <p>Reminders are minutes before the start ({@link Reminders}). What a
 * calendar keeps of them is its store's {@link ReminderSupport}, asked before
 * anything is written: too many or too far ahead is refused with the limit,
 * extras Outlook cannot keep are dropped and named in the result, and a
 * calendar that takes none gets the entry without them and a result that
 * says so — never a "done" for an alarm that does not exist.
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
                "Read one appointment in full: time, place, organiser, attendees, reminders and notes.",
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
                        if (e.reminders() != null && e.reminders().isEmpty()) out.append("  reminders: none\n");
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
                        "attendees", strings("E-mail addresses to invite."),
                        "reminders", minutes("Reminders (alarms) before the start, in minutes: [10] for \"remind me "
                                + "10 minutes before\", [60] for \"an alarm 1 hour before\", [1440] for a day "
                                + "before, [10, 60] for both. For a whole-day entry they count from its midnight. "
                                + "[] creates it with no reminder at all; leave it out and the calendar's default "
                                + "reminder applies. Some calendars keep fewer — the result says what was set.")),
                        "title", "start"),
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
                    List<Integer> asked = reminders(args);
                    try (CalendarStore store = calendars.open(user, account.id())) {
                        if (!store.canCreate()) throw new Refused(account.id() + " cannot create appointments.");
                        String calendar = Optional.ofNullable(str(args, "calendar")).orElseGet(() ->
                                store.calendars().stream().filter(c -> c.primary() && c.writable()).findFirst()
                                        .or(() -> store.calendars().stream().filter(UserCalendar::writable).findFirst())
                                        .map(UserCalendar::id)
                                        .orElseThrow(() -> new Refused(account.id() + " has no calendar to write to.")));
                        Fitted reminders = fit(account, store, asked);
                        String id = store.create(new EventDraft(calendar, required(args, "title"), when,
                                str(args, "location"), str(args, "notes"), list(args, "attendees"),
                                reminders.write()));
                        return "Created \"" + str(args, "title") + "\" in " + account.id() + " (id " + id + ")."
                                + reminders.says();
                    }
                });
    }

    private Tool updateEvent(UserId user, Accounts<ConnectedCalendar> accounts) {
        return new OfficeTool(UPDATE,
                "Change an appointment: move it, rename it, change the place, the notes, who is invited or "
                        + "its reminders. "
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
                        "attendees", strings("The attendees from now on — the whole list."),
                        "reminders", minutes("The reminders from now on, in minutes before the start — the "
                                + "whole list, replacing the old ones: [10] for \"remind me 10 minutes before\", "
                                + "[60] for \"an alarm 1 hour before\", [10, 60] for both. [] removes every "
                                + "reminder; leave it out and the reminders stay as they are.")),
                        "calendar", "id"),
                args -> {
                    ZoneId zone = zones.zoneOf(user);
                    ConnectedCalendar account = accounts.one(args);
                    String calendar = required(args, "calendar");
                    List<Integer> asked = reminders(args);
                    try (CalendarStore store = calendars.open(user, account.id())) {
                        Fitted reminders = fit(account, store, asked);
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
                                        : was.attendees() == null ? List.of() : was.attendees(),
                                reminders.write());
                        store.update(calendar, was.id(), draft);
                        return "Changed \"" + draft.title() + "\"." + reminders.says();
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
        if (e.reminders() != null && !e.reminders().isEmpty()) {
            out.append("  reminders: ").append(Reminders.describe(e.reminders()))
                    .append(" (").append(e.reminders()).append(" minutes)\n");
        }
        return out.toString();
    }

    // ── reminders ───────────────────────────────────────────────────────────

    /** The schema of a list of minutes. */
    static Map<String, Object> minutes(String description) {
        return Map.of("type", "array", "items", Map.of("type", "integer", "minimum", 0),
                "description", description);
    }

    /**
     * The {@code reminders} argument: null when it is not there, else whole
     * minutes, 0 or more. A model writes a JSON array; a string such as
     * {@code "10, 60"} or {@code "[10,60]"} is read the same way.
     */
    static List<Integer> reminders(Map<String, Object> args) {
        Object value = args.get("reminders");
        if (value == null) return null;
        List<Object> items = new ArrayList<>();
        if (value instanceof List<?> list) {
            items.addAll(list);
        } else if (value instanceof Number) {
            items.add(value);
        } else {
            String text = String.valueOf(value).strip();
            if (text.startsWith("[") && text.endsWith("]")) text = text.substring(1, text.length() - 1);
            for (String part : text.split(",")) if (!part.isBlank()) items.add(part.strip());
        }
        List<Integer> minutes = new ArrayList<>();
        for (Object item : items) {
            Integer m = null;
            try {
                if (item instanceof Number n) {
                    BigDecimal exact = new BigDecimal(n.toString());
                    if (exact.stripTrailingZeros().scale() <= 0) m = exact.intValueExact();
                } else if (item != null) {
                    m = Integer.parseInt(String.valueOf(item).strip());
                }
            } catch (ArithmeticException | NumberFormatException e) {
                m = null;
            }
            if (m == null || m < 0) {
                throw new Refused("\"reminders\" are whole minutes before the start, like [10, 60] — not \""
                        + item + "\".");
            }
            minutes.add(m);
        }
        return Reminders.of(minutes);
    }

    /**
     * What to write of the reminders asked for, and the sentence that says
     * what became of them.
     *
     * @param write the minutes for the draft; null leaves the reminders alone
     * @param says  appended to the result, empty when nothing was asked
     */
    record Fitted(List<Integer> write, String says) { }

    /**
     * Asked before the entry is written, so a refusal leaves nothing behind.
     * A calendar that takes no reminders still gets its entry — the result
     * says the reminder is missing rather than failing the whole call.
     */
    static Fitted fit(ConnectedCalendar account, CalendarStore store, List<Integer> asked) {
        if (asked == null) return new Fitted(null, "");
        ReminderSupport support = store.reminders();
        if (!support.writes()) {
            return new Fitted(null, " No reminder was set: " + account.kind() + " calendars cannot take "
                    + "reminders here. Tell the user to set it in their calendar app.");
        }
        ReminderSupport.Fit fit;
        try {
            fit = support.fit(asked);
        } catch (CalendarStoreException e) {
            throw new Refused("Nothing was changed. " + account.id() + ": " + e.getMessage());
        }
        String says = fit.kept().isEmpty() ? " No reminders."
                : " Reminders: " + Reminders.describe(fit.kept()) + ".";
        if (!fit.dropped().isEmpty()) {
            says += " " + account.kind() + " keeps only one reminder per entry, so the "
                    + (fit.dropped().size() == 1 ? "one " : "ones ") + Reminders.describe(fit.dropped())
                    + (fit.dropped().size() == 1 ? " was" : " were") + " dropped.";
        }
        return new Fitted(fit.kept(), says);
    }
}
