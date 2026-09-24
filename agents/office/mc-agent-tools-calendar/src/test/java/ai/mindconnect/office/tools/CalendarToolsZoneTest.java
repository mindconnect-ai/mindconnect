package ai.mindconnect.office.tools;

import ai.mindconnect.agent.UserId;
import ai.mindconnect.agent.tool.Connections;
import ai.mindconnect.agent.tool.MapToolEnvironment;
import ai.mindconnect.agent.tool.TimeZones;
import ai.mindconnect.agent.tool.Tool;
import ai.mindconnect.agent.tool.ToolCallScope;
import ai.mindconnect.agent.tool.ToolConnection;
import ai.mindconnect.calendar.CalendarAccounts;
import ai.mindconnect.calendar.CalendarEvent;
import ai.mindconnect.calendar.CalendarProvider;
import ai.mindconnect.calendar.CalendarStore;
import ai.mindconnect.calendar.EventDraft;
import ai.mindconnect.calendar.EventTime;
import ai.mindconnect.calendar.UserCalendar;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A time the model writes without an offset is a time in the calling user's
 * zone — asked on every call, never the server's — and the tools write times
 * back in the same zone, so the model reads what it wrote.
 */
class CalendarToolsZoneTest {

    private static final UserId ALICE = UserId.of("alice");
    private static final UserId BOB = UserId.of("bob");

    /** One account whose entries are kept in memory; it remembers every draft it was asked to write. */
    private static final class MemoryCalendar implements CalendarStore, CalendarProvider {

        final Map<String, CalendarEvent> events = new HashMap<>();
        final List<EventDraft> written = new ArrayList<>();

        @Override public String provider() { return "caldav"; }
        @Override public CalendarStore open(ToolConnection connection) { return this; }
        @Override public List<UserCalendar> calendars() {
            return List.of(new UserCalendar("main", "Main", null, true, true));
        }
        @Override public List<CalendarEvent> events(String calendarId, Instant from, Instant to, int limit) {
            return List.copyOf(events.values());
        }
        @Override public CalendarEvent read(String calendarId, String eventId) {
            return Optional.ofNullable(events.get(eventId)).orElseThrow();
        }
        @Override public boolean canCreate() { return true; }
        @Override public String create(EventDraft draft) {
            written.add(draft);
            String id = "e" + written.size();
            events.put(id, new CalendarEvent(id, "main", draft.title(), draft.when(), draft.location(), null,
                    draft.attendees(), draft.notes(), false));
            return id;
        }
        @Override public void update(String calendarId, String eventId, EventDraft draft) {
            written.add(draft);
            events.put(eventId, new CalendarEvent(eventId, "main", draft.title(), draft.when(), draft.location(),
                    null, draft.attendees(), draft.notes(), false));
        }
        @Override public void close() { }

        Instant lastStart() {
            return written.get(written.size() - 1).when().start();
        }
    }

    private final MemoryCalendar calendar = new MemoryCalendar();
    /** Whose zone is what — changeable between calls, as a profile page would. */
    private final Map<UserId, ZoneId> zones = new HashMap<>(Map.of(
            ALICE, ZoneId.of("Europe/Zurich"),
            BOB, ZoneId.of("America/New_York")));

    private CalendarAccounts accounts() {
        ToolConnection connection = new ToolConnection() {
            @Override public String key() { return "web"; }
            @Override public String label() { return "Web"; }
            @Override public String provider() { return "caldav"; }
            @Override public String value(String field) { return null; }
            @Override public boolean usable() { return true; }
        };
        Connections connections = new Connections() {
            @Override public List<ToolConnection> of(UserId userId, String provider) {
                return "caldav".equals(provider) ? List.of(connection) : List.of();
            }
            @Override public Optional<ToolConnection> resolve(UserId userId, String provider, String key) {
                return "caldav".equals(provider) ? Optional.of(connection) : Optional.empty();
            }
        };
        return new CalendarAccounts(connections, List.of(calendar));
    }

    /** The provider bound as the runtime binds it: through the environment, which carries the host's resolver. */
    private CalendarToolProvider provider() {
        CalendarToolProvider provider = new CalendarToolProvider();
        provider.bind(MapToolEnvironment.builder()
                .service(CalendarAccounts.class, accounts())
                .service(TimeZones.class, user -> zones.getOrDefault(user, ZoneId.of("UTC")))
                .build());
        return provider;
    }

    private static Tool tool(CalendarToolProvider provider, String name, UserId user) {
        return provider.create(name, null, ToolCallScope.detached(user)).orElseThrow();
    }

    private static Map<String, Object> at(String start) {
        return Map.of("title", "Train", "start", start);
    }

    @Test
    void aLocalTimeInZurichSummerTime_isTwoHoursAheadOfUtc() {
        String result = tool(provider(), "calendar_create", ALICE).execute(at("2026-09-25T16:16"));

        assertThat(result).startsWith("Created");
        // The bug this fixes: on a server in UTC this was 16:16Z, and Google showed 18:16.
        assertThat(calendar.lastStart()).isEqualTo(Instant.parse("2026-09-25T14:16:00Z"));
    }

    @Test
    void aLocalTimeInZurichWinterTime_isOneHourAheadOfUtc() {
        tool(provider(), "calendar_create", ALICE).execute(at("2026-12-15T16:16"));

        assertThat(calendar.lastStart()).isEqualTo(Instant.parse("2026-12-15T15:16:00Z"));
    }

    @Test
    void aLocalTimeInNewYork() {
        tool(provider(), "calendar_create", BOB).execute(at("2026-09-25T16:16"));
        assertThat(calendar.lastStart()).isEqualTo(Instant.parse("2026-09-25T20:16:00Z"));

        tool(provider(), "calendar_create", BOB).execute(at("2026-12-15T16:16"));
        assertThat(calendar.lastStart()).isEqualTo(Instant.parse("2026-12-15T21:16:00Z"));
    }

    @Test
    void aTimeWithAnOffsetIsTakenAsWritten() {
        tool(provider(), "calendar_create", ALICE).execute(at("2026-09-25T16:16+09:00"));

        assertThat(calendar.lastStart()).isEqualTo(Instant.parse("2026-09-25T07:16:00Z"));
    }

    @Test
    void twoUsers_oneProvider_eachCallInItsOwnUsersZone() {
        CalendarToolProvider provider = provider();

        tool(provider, "calendar_create", ALICE).execute(at("2026-09-25T09:00"));
        Instant alices = calendar.lastStart();
        tool(provider, "calendar_create", BOB).execute(at("2026-09-25T09:00"));
        Instant bobs = calendar.lastStart();

        assertThat(alices).isEqualTo(Instant.parse("2026-09-25T07:00:00Z"));
        assertThat(bobs).isEqualTo(Instant.parse("2026-09-25T13:00:00Z"));
    }

    @Test
    void theZoneIsAskedOnEveryCall_notWhenTheToolWasMade() {
        Tool create = tool(provider(), "calendar_create", ALICE);
        create.execute(at("2026-09-25T09:00"));
        assertThat(calendar.lastStart()).isEqualTo(Instant.parse("2026-09-25T07:00:00Z"));

        // Alice moves to Tokyo and says so on her profile page; the same tool object follows.
        zones.put(ALICE, ZoneId.of("Asia/Tokyo"));
        create.execute(at("2026-09-25T09:00"));
        assertThat(calendar.lastStart()).isEqualTo(Instant.parse("2026-09-25T00:00:00Z"));
    }

    @Test
    void whatTheToolsShowIsInTheSameZone_soTheModelReadsBackWhatItWrote() {
        CalendarToolProvider provider = provider();
        tool(provider, "calendar_create", ALICE).execute(at("2026-09-25T16:16"));

        String alices = tool(provider, "calendar_events", ALICE)
                .execute(Map.of("from", "2026-09-25", "to", "2026-09-26"));
        String bobs = tool(provider, "calendar_read", BOB).execute(Map.of("calendar", "main", "id", "e1"));

        assertThat(alices).contains("2026-09-25 16:16 – 17:16  Train")
                .contains("between 2026-09-25 00:00 and 2026-09-26 00:00");
        // The same train, as Bob in New York reads it.
        assertThat(bobs).contains("2026-09-25 10:16 – 11:16  Train");
    }

    @Test
    void movingAnEntry_readsTheNewStartInTheUsersZone() {
        CalendarToolProvider provider = provider();
        tool(provider, "calendar_create", ALICE).execute(at("2026-09-25T16:16"));

        tool(provider, "calendar_update", ALICE).execute(Map.of("calendar", "main", "id", "e1",
                "start", "2026-12-15T08:30"));

        assertThat(calendar.lastStart()).isEqualTo(Instant.parse("2026-12-15T07:30:00Z"));
        assertThat(calendar.written.get(1).when().end()).isEqualTo(Instant.parse("2026-12-15T08:30:00Z"));
    }

    @Test
    void withoutAResolverTheJvmsZoneApplies_asBefore() {
        CalendarToolProvider provider = new CalendarToolProvider().bind(accounts());

        tool(provider, "calendar_create", ALICE).execute(at("2026-09-25T16:16"));

        assertThat(calendar.lastStart()).isEqualTo(
                java.time.LocalDateTime.parse("2026-09-25T16:16").atZone(ZoneId.systemDefault()).toInstant());
        // A fixed zone, as a host without users or a test passes it.
        CalendarTools fixed = new CalendarTools(accounts(), ZoneId.of("Europe/Zurich"));
        fixed.create("calendar_create", BOB).orElseThrow().execute(at("2026-09-25T16:16"));
        assertThat(calendar.lastStart()).isEqualTo(Instant.parse("2026-09-25T14:16:00Z"));
    }

    @Test
    void anAllDayEntryStaysADate() {
        tool(provider(), "calendar_create", BOB).execute(at("2026-09-25"));

        EventTime when = calendar.written.get(0).when();
        assertThat(when.allDay()).isTrue();
        assertThat(when.startDate()).isEqualTo(java.time.LocalDate.parse("2026-09-25"));
    }
}
