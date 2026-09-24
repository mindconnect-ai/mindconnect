package ai.mindconnect.office.tools;

import ai.mindconnect.agent.UserId;
import ai.mindconnect.agent.tool.Connections;
import ai.mindconnect.agent.tool.Tool;
import ai.mindconnect.agent.tool.ToolCallScope;
import ai.mindconnect.agent.tool.ToolConnection;
import ai.mindconnect.calendar.CalendarAccounts;
import ai.mindconnect.calendar.CalendarEvent;
import ai.mindconnect.calendar.CalendarProvider;
import ai.mindconnect.calendar.CalendarStore;
import ai.mindconnect.calendar.EventDraft;
import ai.mindconnect.calendar.EventTime;
import ai.mindconnect.calendar.ReminderSupport;
import ai.mindconnect.calendar.UserCalendar;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Reminders through the calendar tools: what the model is told about them,
 * what reaches the store, and what the result says became of them — for a
 * calendar that keeps many, one that keeps one, one with limits, and one that
 * keeps none.
 */
class CalendarToolsTest {

    private static final UserId ME = new UserId("me");

    /** One account of a kind, holding its entries in memory and remembering what it was asked to write. */
    private static final class MemoryCalendar implements CalendarStore, CalendarProvider {

        final String provider;
        final ReminderSupport support;
        final Map<String, CalendarEvent> events = new HashMap<>();
        final List<EventDraft> written = new ArrayList<>();

        MemoryCalendar(String provider, ReminderSupport support) {
            this.provider = provider;
            this.support = support;
        }

        @Override public String provider() {
            return provider;
        }

        @Override public CalendarStore open(ToolConnection connection) {
            return this;
        }

        @Override public List<UserCalendar> calendars() {
            return List.of(new UserCalendar("main", "Main", null, true, true));
        }

        @Override public List<CalendarEvent> events(String calendarId, Instant from, Instant to, int limit) {
            return List.copyOf(events.values());
        }

        @Override public CalendarEvent read(String calendarId, String eventId) {
            return Optional.ofNullable(events.get(eventId)).orElseThrow();
        }

        @Override public boolean canCreate() {
            return true;
        }

        @Override public String create(EventDraft draft) {
            written.add(draft);
            String id = "e" + written.size();
            events.put(id, event(id, draft));
            return id;
        }

        @Override public void update(String calendarId, String eventId, EventDraft draft) {
            written.add(draft);
            CalendarEvent was = events.get(eventId);
            events.put(eventId, event(eventId, draft.reminders() == null
                    ? draft.withReminders(was.reminders()) : draft));
        }

        @Override public ReminderSupport reminders() {
            return support;
        }

        @Override public void close() { }

        private static CalendarEvent event(String id, EventDraft draft) {
            return new CalendarEvent(id, "main", draft.title(), draft.when(), draft.location(), null,
                    draft.attendees(), draft.notes(), false, draft.reminders());
        }

        EventDraft last() {
            return written.get(written.size() - 1);
        }
    }

    private static Tool tool(MemoryCalendar calendar, String name) {
        ToolConnection connection = new ToolConnection() {
            @Override public String key() {
                return "work";
            }

            @Override public String label() {
                return "Work";
            }

            @Override public String provider() {
                return calendar.provider;
            }

            @Override public String value(String field) {
                return null;
            }

            @Override public boolean usable() {
                return true;
            }
        };
        Connections connections = new Connections() {
            @Override public List<ToolConnection> of(UserId userId, String provider) {
                return provider.equals(calendar.provider) ? List.of(connection) : List.of();
            }

            @Override public Optional<ToolConnection> resolve(UserId userId, String provider, String key) {
                return provider.equals(calendar.provider) ? Optional.of(connection) : Optional.empty();
            }
        };
        return new CalendarToolProvider().bind(new CalendarAccounts(connections, List.of(calendar)))
                .create(name, null, ToolCallScope.detached(ME)).orElseThrow();
    }

    private static Map<String, Object> call(Object reminders) {
        Map<String, Object> args = new HashMap<>(Map.of("title", "Zahnarzt", "start", "2026-09-25T10:00"));
        if (reminders != null) args.put("reminders", reminders);
        return args;
    }

    @Test
    @SuppressWarnings("unchecked")
    void create_and_update_take_reminders_as_minutes_and_say_what_leaving_them_out_means() {
        MemoryCalendar caldav = new MemoryCalendar("caldav", ReminderSupport.ANY);

        for (String name : List.of("calendar_create", "calendar_update")) {
            Map<String, Object> properties = (Map<String, Object>) tool(caldav, name).parametersSchema().get("properties");
            Map<String, Object> reminders = (Map<String, Object>) properties.get("reminders");
            assertThat(reminders.get("type")).isEqualTo("array");
            assertThat((Map<String, Object>) reminders.get("items")).containsEntry("type", "integer")
                    .containsEntry("minimum", 0);
            assertThat((String) reminders.get("description"))
                    .contains("remind me 10 minutes before").contains("1 hour before").contains("[]");
        }
        Map<String, Object> create = (Map<String, Object>) tool(caldav, "calendar_create").parametersSchema().get("properties");
        Map<String, Object> update = (Map<String, Object>) tool(caldav, "calendar_update").parametersSchema().get("properties");
        assertThat((String) ((Map<String, Object>) create.get("reminders")).get("description"))
                .contains("no reminder at all").contains("calendar's default");
        assertThat((String) ((Map<String, Object>) update.get("reminders")).get("description"))
                .contains("removes every reminder").contains("stay as they are");
        assertThat(tool(caldav, "calendar_read").description()).contains("reminders");
    }

    @Test
    void a_calendar_that_keeps_them_all_gets_them_all() {
        MemoryCalendar caldav = new MemoryCalendar("caldav", ReminderSupport.ANY);

        String result = tool(caldav, "calendar_create").execute(call(List.of(60, 10)));

        assertThat(result).startsWith("Created \"Zahnarzt\" in caldav.work (id e1).")
                .endsWith("Reminders: 10 minutes and 1 hour before.");
        assertThat(caldav.last().reminders()).containsExactly(10, 60);
    }

    @Test
    void no_reminders_is_not_the_same_as_leaving_them_out() {
        MemoryCalendar caldav = new MemoryCalendar("caldav", ReminderSupport.ANY);

        String none = tool(caldav, "calendar_create").execute(call(List.of()));
        assertThat(caldav.last().reminders()).isEmpty();
        String untouched = tool(caldav, "calendar_create").execute(call(null));
        assertThat(caldav.last().reminders()).isNull();

        assertThat(none).endsWith("No reminders.");
        assertThat(untouched).endsWith("(id e2).");
    }

    @Test
    void reminders_written_as_text_are_read_as_numbers_and_nonsense_is_refused() {
        MemoryCalendar caldav = new MemoryCalendar("caldav", ReminderSupport.ANY);
        Tool create = tool(caldav, "calendar_create");

        create.execute(call("10, 60"));
        assertThat(caldav.last().reminders()).containsExactly(10, 60);
        create.execute(call("[15]"));
        assertThat(caldav.last().reminders()).containsExactly(15);
        create.execute(call(List.of(30.0, "45")));
        assertThat(caldav.last().reminders()).containsExactly(30, 45);

        assertThat(create.execute(call(List.of("1h")))).startsWith("Error: \"reminders\" are whole minutes");
        assertThat(create.execute(call(List.of(-10)))).startsWith("Error: \"reminders\" are whole minutes");
        assertThat(create.execute(call(List.of(7.5)))).startsWith("Error: \"reminders\" are whole minutes");
        assertThat(caldav.written).hasSize(3);
    }

    @Test
    void outlook_keeps_the_one_closest_to_the_start_and_the_result_names_the_rest() {
        MemoryCalendar outlook = new MemoryCalendar("microsoft", ReminderSupport.onlyOne(Integer.MAX_VALUE));

        String two = tool(outlook, "calendar_create").execute(call(List.of(60, 10)));
        assertThat(outlook.last().reminders()).containsExactly(10);
        assertThat(two).contains("Reminders: 10 minutes before.")
                .contains("Outlook keeps only one reminder per entry, so the one 1 hour before was dropped.");

        String three = tool(outlook, "calendar_create").execute(call(List.of(1440, 60, 10)));
        assertThat(three).contains("so the ones 1 hour and 1 day before were dropped.");

        String one = tool(outlook, "calendar_create").execute(call(List.of(15)));
        assertThat(one).endsWith("Reminders: 15 minutes before.").doesNotContain("dropped");
    }

    @Test
    void more_than_a_calendar_allows_is_refused_before_anything_is_written() {
        MemoryCalendar google = new MemoryCalendar("google", ReminderSupport.upTo(5, 40_320));

        String many = tool(google, "calendar_create").execute(call(List.of(1, 2, 3, 4, 5, 6)));
        String far = tool(google, "calendar_create").execute(call(List.of(50_000)));

        assertThat(many).startsWith("Error: Nothing was changed. google.work: ")
                .contains("at most 5 reminders per entry, not 6");
        assertThat(far).contains("at most 40320 minutes (4 weeks) before the start, not 50000");
        assertThat(google.written).isEmpty();
    }

    @Test
    void a_calendar_that_takes_no_reminders_still_gets_the_entry_and_the_result_says_the_reminder_is_missing() {
        MemoryCalendar none = new MemoryCalendar("google", ReminderSupport.NONE);

        String result = tool(none, "calendar_create").execute(call(List.of(60)));

        assertThat(none.last().reminders()).isNull();
        assertThat(result).startsWith("Created \"Zahnarzt\"")
                .contains("No reminder was set: Google calendars cannot take reminders here.");
    }

    @Test
    void update_replaces_reminders_only_when_it_names_them_and_read_shows_them() {
        MemoryCalendar caldav = new MemoryCalendar("caldav", ReminderSupport.ANY);
        tool(caldav, "calendar_create").execute(call(List.of(10)));
        Tool update = tool(caldav, "calendar_update");
        Tool read = tool(caldav, "calendar_read");

        String renamed = update.execute(Map.of("calendar", "main", "id", "e1", "title", "Zahnarzt Dr. Weber"));
        assertThat(caldav.last().reminders()).as("left alone").isNull();
        assertThat(renamed).isEqualTo("Changed \"Zahnarzt Dr. Weber\".");
        assertThat(read.execute(Map.of("calendar", "main", "id", "e1")))
                .contains("reminders: 10 minutes before ([10] minutes)");

        String hour = update.execute(Map.of("calendar", "main", "id", "e1", "reminders", List.of(60)));
        assertThat(hour).isEqualTo("Changed \"Zahnarzt Dr. Weber\". Reminders: 1 hour before.");
        assertThat(caldav.last().reminders()).containsExactly(60);

        update.execute(Map.of("calendar", "main", "id", "e1", "reminders", List.of()));
        assertThat(caldav.last().reminders()).isEmpty();
        assertThat(read.execute(Map.of("calendar", "main", "id", "e1"))).contains("reminders: none");
    }

    @Test
    void a_listing_shows_reminders_where_the_calendar_says_them() {
        MemoryCalendar caldav = new MemoryCalendar("caldav", ReminderSupport.ANY);
        tool(caldav, "calendar_create").execute(call(List.of(5, 1440)));
        caldav.events.put("e9", new CalendarEvent("e9", "main", "Lunch",
                EventTime.at(Instant.parse("2026-09-25T10:00:00Z"), Instant.parse("2026-09-25T11:00:00Z")),
                null, null, null, null, false));

        String listing = tool(caldav, "calendar_events").execute(Map.of("from", "2026-09-25", "to", "2026-09-26"));

        assertThat(listing).contains("reminders: 5 minutes and 1 day before ([5, 1440] minutes)")
                .containsOnlyOnce("reminders:");
    }

    @Test
    void the_argument_is_read_the_same_way_whatever_the_model_sends() {
        assertThat(CalendarTools.reminders(Map.of())).isNull();
        assertThat(CalendarTools.reminders(Map.of("reminders", 30))).containsExactly(30);
        assertThat(CalendarTools.reminders(Map.of("reminders", ""))).isEmpty();
        assertThat(CalendarTools.reminders(Map.of("reminders", "[]"))).isEmpty();
        assertThatThrownBy(() -> CalendarTools.reminders(Map.of("reminders", "soon")))
                .hasMessageContaining("[10, 60]");
    }
}
