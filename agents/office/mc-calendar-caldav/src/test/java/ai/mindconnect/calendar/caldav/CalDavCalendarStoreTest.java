package ai.mindconnect.calendar.caldav;

import ai.mindconnect.agent.tool.ToolConnection;
import ai.mindconnect.calendar.CalendarEvent;
import ai.mindconnect.calendar.CalendarStoreException;
import ai.mindconnect.calendar.EventDraft;
import ai.mindconnect.calendar.EventTime;
import ai.mindconnect.calendar.ReminderSupport;
import ai.mindconnect.calendar.UserCalendar;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** A CalDAV account as a calendar: what it reads, what it writes, and what it says when it cannot. */
class CalDavCalendarStoreTest {

    private static final ZoneId ZURICH = ZoneId.of("Europe/Zurich");

    private FakeDav dav;
    private CalDavClient client;

    @BeforeEach
    void setUp() throws IOException {
        dav = new FakeDav();
        client = new CalDavClient();
    }

    @AfterEach
    void tearDown() {
        dav.close();
    }

    private CalDavCalendarStore store(String path) {
        return new CalDavCalendarStore(client, new CalDavAccount(dav.url(path), "me@web.de", "secret"), ZURICH);
    }

    @Test
    void the_home_address_answers_with_the_calendars_under_it() {
        dav.answers("""
                <d:multistatus xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav">
                  <d:response>
                    <d:href>/dav/me/</d:href>
                    <d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype>
                      <d:displayname>Home</d:displayname></d:prop></d:propstat>
                  </d:response>
                  <d:response>
                    <d:href>/dav/me/calendar/</d:href>
                    <d:propstat><d:prop>
                      <d:resourcetype><d:collection/><c:calendar/></d:resourcetype>
                      <d:displayname>Privat</d:displayname>
                      <c:supported-calendar-component-set><c:comp name="VEVENT"/></c:supported-calendar-component-set>
                    </d:prop></d:propstat>
                  </d:response>
                  <d:response>
                    <d:href>/dav/me/tasks/</d:href>
                    <d:propstat><d:prop>
                      <d:resourcetype><d:collection/><c:calendar/></d:resourcetype>
                      <d:displayname>Aufgaben</d:displayname>
                      <c:supported-calendar-component-set><c:comp name="VTODO"/></c:supported-calendar-component-set>
                    </d:prop></d:propstat>
                  </d:response>
                </d:multistatus>
                """);

        List<UserCalendar> calendars = store("/dav/me/").calendars();

        // The home itself is no calendar, and a collection of todos is not one either.
        assertThat(calendars).extracting(UserCalendar::name).containsExactly("Privat");
        assertThat(calendars.get(0).id()).endsWith("/dav/me/calendar/");
        assertThat(calendars.get(0).primary()).isTrue();
        assertThat(dav.call(0).method()).isEqualTo("PROPFIND");
        assertThat(dav.call(0).depth()).isEqualTo("1");
        assertThat(dav.call(0).authorization()).startsWith("Basic ");
    }

    @Test
    void an_address_that_is_itself_a_calendar_is_the_one_calendar() {
        dav.answers("<d:multistatus xmlns:d=\"DAV:\"/>");

        List<UserCalendar> calendars = store("/dav/me/calendar/").calendars();

        assertThat(calendars).singleElement().satisfies(c -> {
            assertThat(c.name()).isEqualTo("calendar");
            assertThat(c.writable()).isTrue();
        });
    }

    @Test
    void the_appointments_of_a_period_come_back_in_order_with_their_details() {
        // The data is written without indentation on purpose: a line that
        // starts with a space is a folded continuation of the one before it.
        dav.answers("<d:multistatus xmlns:d=\"DAV:\"/>",
                "<d:multistatus xmlns:d=\"DAV:\" xmlns:c=\"urn:ietf:params:xml:ns:caldav\">"
                + "<d:response><d:href>/dav/me/calendar/b.ics</d:href><d:propstat><d:prop>"
                + "<c:calendar-data>"
                + "BEGIN:VCALENDAR\nBEGIN:VEVENT\n"
                + "UID:zweiter\nSUMMARY:Zahnarzt\n"
                + "DTSTART;TZID=Europe/Zurich:20260924T140000\n"
                + "DTEND;TZID=Europe/Zurich:20260924T150000\n"
                + "LOCATION:Bahnhofstrasse 1\\, Zürich\n"
                + "END:VEVENT\nEND:VCALENDAR"
                + "</c:calendar-data></d:prop></d:propstat></d:response>"
                + "<d:response><d:href>/dav/me/calendar/a.ics</d:href><d:propstat><d:prop>"
                + "<c:calendar-data>"
                + "BEGIN:VCALENDAR\nBEGIN:VEVENT\n"
                + "UID:erster\nSUMMARY:Ferien\n"
                + "DTSTART;VALUE=DATE:20260923\nDTEND;VALUE=DATE:20260925\n"
                + "DESCRIPTION:Zwei Tage\\nfrei\n"
                + "ATTENDEE;ROLE=REQ-PARTICIPANT:mailto:anna@example.com\n"
                + "END:VEVENT\nEND:VCALENDAR"
                + "</c:calendar-data></d:prop></d:propstat></d:response>"
                + "</d:multistatus>");

        List<CalendarEvent> events = store("/dav/me/calendar/").events(null,
                Instant.parse("2026-09-23T00:00:00Z"), Instant.parse("2026-09-26T00:00:00Z"), 50);

        assertThat(events).extracting(CalendarEvent::title).containsExactly("Ferien", "Zahnarzt");
        CalendarEvent holiday = events.get(0);
        assertThat(holiday.id()).isEqualTo("erster");
        assertThat(holiday.when().allDay()).isTrue();
        // iCalendar's end is the morning after; a person's last day is the day before that.
        assertThat(holiday.when().startDate()).isEqualTo(LocalDate.of(2026, 9, 23));
        assertThat(holiday.when().endDate()).isEqualTo(LocalDate.of(2026, 9, 24));
        assertThat(holiday.notes()).isEqualTo("Zwei Tage\nfrei");
        assertThat(holiday.attendees()).containsExactly("anna@example.com");

        CalendarEvent dentist = events.get(1);
        assertThat(dentist.when().start()).isEqualTo(Instant.parse("2026-09-24T12:00:00Z"));
        assertThat(dentist.when().end()).isEqualTo(Instant.parse("2026-09-24T13:00:00Z"));
        assertThat(dentist.location()).isEqualTo("Bahnhofstrasse 1, Zürich");

        // The query names the period the caller asked for.
        assertThat(dav.call(1).method()).isEqualTo("REPORT");
        assertThat(dav.call(1).body()).contains("20260923T000000Z").contains("20260926T000000Z")
                .contains("VEVENT");
    }

    @Test
    void a_new_appointment_is_a_file_in_the_calendar() {
        dav.answers("<d:multistatus xmlns:d=\"DAV:\"/>", "");

        String id = store("/dav/me/calendar/").create(new EventDraft(null, "Rückruf Anna",
                EventTime.at(Instant.parse("2026-09-25T08:00:00Z"), Instant.parse("2026-09-25T08:30:00Z")),
                "Telefon", "Wegen des Angebots", List.of("anna@example.com")));

        FakeDav.Call put = dav.call(1);
        assertThat(put.method()).isEqualTo("PUT");
        assertThat(put.path()).isEqualTo("/dav/me/calendar/" + id + ".ics");
        assertThat(put.body())
                .contains("BEGIN:VEVENT").contains("UID:" + id)
                .contains("SUMMARY:Rückruf Anna")
                .contains("DTSTART:20260925T080000Z").contains("DTEND:20260925T083000Z")
                .contains("LOCATION:Telefon")
                .contains("ATTENDEE;ROLE=REQ-PARTICIPANT:mailto:anna@example.com");
    }

    @Test
    void changing_one_writes_the_file_it_read_and_deleting_removes_it() {
        dav.answers("<d:multistatus xmlns:d=\"DAV:\"/>")
                .answer(200, "BEGIN:VCALENDAR\r\nBEGIN:VEVENT\r\nUID:erster\r\nSUMMARY:Ferien\r\n"
                        + "DTSTART;VALUE=DATE:20260923\r\nDTEND;VALUE=DATE:20260925\r\n"
                        + "END:VEVENT\r\nEND:VCALENDAR\r\n", "\"v1\"")
                .answer(204, "", null)
                .answer(200, "BEGIN:VCALENDAR\r\nBEGIN:VEVENT\r\nUID:erster\r\nSUMMARY:Ferien\r\n"
                        + "DTSTART;VALUE=DATE:20260923\r\nDTEND;VALUE=DATE:20260928\r\n"
                        + "END:VEVENT\r\nEND:VCALENDAR\r\n", "\"v2\"")
                .answer(204, "", null);

        CalDavCalendarStore store = store("/dav/me/calendar/");
        store.update(null, "erster", new EventDraft(null, "Ferien (verlängert)",
                EventTime.on(LocalDate.of(2026, 9, 23), LocalDate.of(2026, 9, 27)), null, null, List.of()));
        store.delete(null, "erster");

        assertThat(dav.call(1).method()).isEqualTo("GET");
        assertThat(dav.call(2).method()).isEqualTo("PUT");
        assertThat(dav.call(2).path()).endsWith("/erster.ics");
        // Only if nobody changed it since it was read.
        assertThat(dav.call(2).ifMatch()).isEqualTo("\"v1\"");
        // An all-day event ends the morning after its last day.
        assertThat(dav.call(2).body()).contains("DTSTART;VALUE=DATE:20260923")
                .contains("DTEND;VALUE=DATE:20260928").contains("SUMMARY:Ferien (verlängert)")
                .doesNotContain("DTEND;VALUE=DATE:20260925");
        assertThat(dav.call(4).method()).isEqualTo("DELETE");
        assertThat(dav.call(4).path()).endsWith("/erster.ics");
        assertThat(dav.call(4).ifMatch()).isEqualTo("\"v2\"");
    }

    @Test
    void reminders_are_written_as_alarms_read_back_and_replaced_by_a_change() {
        dav.answers("<d:multistatus xmlns:d=\"DAV:\"/>", "");
        CalDavCalendarStore store = store("/dav/me/calendar/");

        assertThat(store.reminders()).isEqualTo(ReminderSupport.ANY);
        String id = store.create(new EventDraft(null, "Zahnarzt",
                EventTime.at(Instant.parse("2026-09-25T08:00:00Z"), Instant.parse("2026-09-25T09:00:00Z")),
                null, null, List.of(), List.of(60, 10)));

        String created = dav.call(1).body();
        assertThat(created).contains("BEGIN:VALARM\r\nACTION:DISPLAY\r\nDESCRIPTION:Zahnarzt\r\n"
                + "TRIGGER:-PT10M\r\nEND:VALARM").contains("TRIGGER:-PT60M");

        // Read it back as the server holds it, then give it one reminder instead of two.
        dav.answer(200, created, "\"v1\"").answer(204, "", null);
        String calendar = dav.url("/dav/me/calendar/").toString();
        CalendarEvent was = store.read(calendar, id);
        store.update(calendar, id, new EventDraft(null, was.title(), was.when(), null, null, List.of(),
                List.of(1440)));

        assertThat(was.reminders()).containsExactly(10, 60);
        FakeDav.Call put = dav.call(3);
        assertThat(put.method()).isEqualTo("PUT");
        assertThat(put.ifMatch()).isEqualTo("\"v1\"");
        assertThat(put.body()).contains("TRIGGER:-PT1440M").doesNotContain("-PT10M").doesNotContain("-PT60M")
                .containsOnlyOnce("BEGIN:VALARM");
    }

    @Test
    void a_listing_shows_each_appointments_reminders() {
        dav.answers("<d:multistatus xmlns:d=\"DAV:\" xmlns:c=\"urn:ietf:params:xml:ns:caldav\">"
                + "<d:response><d:href>/dav/me/calendar/a.ics</d:href><d:propstat><d:prop>"
                + "<c:calendar-data>"
                + "BEGIN:VCALENDAR\nBEGIN:VEVENT\nUID:a\nSUMMARY:Standup\n"
                + "DTSTART:20260924T070000Z\nDTEND:20260924T071500Z\n"
                + "BEGIN:VALARM\nACTION:DISPLAY\nDESCRIPTION:Standup\nTRIGGER:-PT5M\nEND:VALARM\n"
                + "END:VEVENT\nEND:VCALENDAR"
                + "</c:calendar-data></d:prop></d:propstat></d:response></d:multistatus>");

        List<CalendarEvent> events = store("/dav/me/calendar/").events(dav.url("/dav/me/calendar/").toString(),
                Instant.parse("2026-09-24T00:00:00Z"), Instant.parse("2026-09-25T00:00:00Z"), 10);

        assertThat(events).singleElement().satisfies(e -> {
            assertThat(e.reminders()).containsExactly(5);
            assertThat(e.notes()).isNull();
        });
    }

    @Test
    void a_change_somebody_made_in_between_fails_instead_of_being_overwritten() {
        dav.answer(200, "BEGIN:VCALENDAR\r\nBEGIN:VEVENT\r\nUID:erster\r\nSUMMARY:Ferien\r\n"
                        + "DTSTART;VALUE=DATE:20260923\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n", "\"v1\"")
                .answer(412, "", null);
        CalDavCalendarStore store = store("/dav/me/calendar/");
        String calendar = dav.url("/dav/me/calendar/").toString();

        CalendarEvent was = store.read(calendar, "erster");

        assertThatThrownBy(() -> store.update(calendar, "erster", new EventDraft(null, "Urlaub",
                was.when(), null, null, List.of())))
                .isInstanceOf(CalendarStoreException.class)
                .hasMessageContaining("changed on the server");
        assertThat(dav.call(1).ifMatch()).isEqualTo("\"v1\"");
    }

    @Test
    void an_appointment_filed_under_another_name_is_found_by_its_uid_and_written_back_there() {
        // Apple, Outlook and DAVx5 name the file themselves; <UID>.ics is empty.
        dav.answer(404, "", null)
                .answers("<d:multistatus xmlns:d=\"DAV:\" xmlns:c=\"urn:ietf:params:xml:ns:caldav\">"
                        + "<d:response><d:href>/dav/me/calendar/1F2E-3D4C.ics</d:href><d:propstat><d:prop>"
                        + "<d:getetag>&quot;e1&quot;</d:getetag>"
                        + "<c:calendar-data>"
                        + "BEGIN:VCALENDAR\nBEGIN:VEVENT\nUID:abc@apple\nSUMMARY:Coiffeur\n"
                        + "DTSTART:20260924T080000Z\nDTEND:20260924T090000Z\nEND:VEVENT\nEND:VCALENDAR"
                        + "</c:calendar-data></d:prop></d:propstat></d:response></d:multistatus>")
                .answer(204, "", null);
        CalDavCalendarStore store = store("/dav/me/calendar/");
        String calendar = dav.url("/dav/me/calendar/").toString();

        CalendarEvent was = store.read(calendar, "abc@apple");
        store.update(calendar, "abc@apple", new EventDraft(null, "Coiffeur Anna", was.when(), null, null,
                List.of()));

        assertThat(was.title()).isEqualTo("Coiffeur");
        assertThat(dav.call(0).method()).isEqualTo("GET");
        assertThat(dav.call(0).path()).isEqualTo("/dav/me/calendar/abc@apple.ics");
        assertThat(dav.call(1).method()).isEqualTo("REPORT");
        assertThat(dav.call(1).body()).contains("<c:prop-filter name=\"UID\">").contains(">abc@apple<");
        FakeDav.Call put = dav.call(2);
        assertThat(put.method()).isEqualTo("PUT");
        assertThat(put.path()).isEqualTo("/dav/me/calendar/1F2E-3D4C.ics");
        assertThat(put.ifMatch()).isEqualTo("\"e1\"");
        assertThat(put.body()).contains("UID:abc@apple").contains("SUMMARY:Coiffeur Anna");
    }

    @Test
    void the_listing_asks_the_server_for_the_occurrences_of_a_recurring_appointment() {
        dav.answers("<d:multistatus xmlns:d=\"DAV:\" xmlns:c=\"urn:ietf:params:xml:ns:caldav\">"
                + "<d:response><d:href>/dav/me/calendar/weekly.ics</d:href><d:propstat><d:prop>"
                + "<c:calendar-data>"
                + "BEGIN:VCALENDAR\nBEGIN:VEVENT\nUID:weekly\nSUMMARY:Jour fixe\n"
                + "RECURRENCE-ID:20260923T080000Z\n"
                + "DTSTART:20260923T080000Z\nDTEND:20260923T090000Z\nEND:VEVENT\n"
                + "BEGIN:VEVENT\nUID:weekly\nSUMMARY:Jour fixe\n"
                + "RECURRENCE-ID:20260930T080000Z\n"
                + "DTSTART:20260930T080000Z\nDTEND:20260930T090000Z\nEND:VEVENT\nEND:VCALENDAR"
                + "</c:calendar-data></d:prop></d:propstat></d:response></d:multistatus>");
        String calendar = dav.url("/dav/me/calendar/").toString();

        List<CalendarEvent> events = store("/dav/me/calendar/").events(calendar,
                Instant.parse("2026-09-21T00:00:00Z"), Instant.parse("2026-10-05T00:00:00Z"), 50);

        assertThat(dav.call(0).body())
                .contains("<c:expand start=\"20260921T000000Z\" end=\"20261005T000000Z\"/>");
        assertThat(events).extracting(e -> e.when().start()).containsExactly(
                Instant.parse("2026-09-23T08:00:00Z"), Instant.parse("2026-09-30T08:00:00Z"));
    }

    @Test
    void changing_a_recurring_appointment_keeps_its_rule_and_what_the_draft_does_not_know() {
        String original = String.join("\r\n",
                "BEGIN:VCALENDAR",
                "VERSION:2.0",
                "PRODID:-//Apple Inc.//iCal//EN",
                "BEGIN:VTIMEZONE",
                "TZID:Europe/Zurich",
                "END:VTIMEZONE",
                "BEGIN:VEVENT",
                "UID:weekly",
                "SEQUENCE:2",
                "DTSTAMP:20250101T000000Z",
                "SUMMARY:Jour fixe",
                "DTSTART;TZID=Europe/Zurich:20250106T100000",
                "DTEND;TZID=Europe/Zurich:20250106T110000",
                "RRULE:FREQ=WEEKLY;BYDAY=MO",
                "EXDATE;TZID=Europe/Zurich:20250113T100000",
                "DESCRIPTION:A long description that the server folded because it is longer",
                "  than seventy-five characters",
                "ATTENDEE;CN=Anna;PARTSTAT=ACCEPTED:mailto:anna@example.com",
                "X-APPLE-TRAVEL-ADVISORY-BEHAVIOR:AUTOMATIC",
                "BEGIN:VALARM",
                "ACTION:DISPLAY",
                "DESCRIPTION:Reminder",
                "TRIGGER:-PT15M",
                "END:VALARM",
                "END:VEVENT",
                "BEGIN:VEVENT",
                "UID:weekly",
                "RECURRENCE-ID;TZID=Europe/Zurich:20250120T100000",
                "SUMMARY:Jour fixe (verschoben)",
                "DTSTART;TZID=Europe/Zurich:20250120T140000",
                "DTEND;TZID=Europe/Zurich:20250120T150000",
                "END:VEVENT",
                "END:VCALENDAR", "");
        dav.answer(200, original, "\"r1\"").answer(204, "", null);
        CalDavCalendarStore store = store("/dav/me/calendar/");
        String calendar = dav.url("/dav/me/calendar/").toString();

        CalendarEvent was = store.read(calendar, "weekly");
        // The series, not its moved occurrence.
        assertThat(was.title()).isEqualTo("Jour fixe");

        assertThatThrownBy(() -> store.update(calendar, "weekly", new EventDraft(null, was.title(),
                EventTime.at(Instant.parse("2026-09-28T09:00:00Z"), Instant.parse("2026-09-28T10:00:00Z")),
                was.location(), was.notes(), was.attendees())))
                .isInstanceOf(CalendarStoreException.class)
                .hasMessageContaining("recurring");
        assertThat(dav.calls()).isEqualTo(1);

        store.update(calendar, "weekly", new EventDraft(null, "Jour fixe Team", was.when(), was.location(),
                was.notes(), List.of("anna@example.com", "bob@example.com")));

        String put = dav.call(1).body();
        assertThat(put)
                .contains("RRULE:FREQ=WEEKLY;BYDAY=MO")
                .contains("EXDATE;TZID=Europe/Zurich:20250113T100000")
                .contains("DTSTART;TZID=Europe/Zurich:20250106T100000")
                .contains("DTEND;TZID=Europe/Zurich:20250106T110000")
                .contains("BEGIN:VTIMEZONE")
                .contains("X-APPLE-TRAVEL-ADVISORY-BEHAVIOR:AUTOMATIC")
                .contains("DESCRIPTION:A long description that the server folded because it is longer\r\n"
                        + "  than seventy-five characters")
                .contains("DESCRIPTION:Reminder")
                .contains("ATTENDEE;CN=Anna;PARTSTAT=ACCEPTED:mailto:anna@example.com")
                .contains("ATTENDEE;ROLE=REQ-PARTICIPANT:mailto:bob@example.com")
                .contains("SUMMARY:Jour fixe Team")
                .contains("SEQUENCE:3")
                .contains("SUMMARY:Jour fixe (verschoben)")
                .contains("RECURRENCE-ID;TZID=Europe/Zurich:20250120T100000")
                .doesNotContain("SUMMARY:Jour fixe\r\n")
                .doesNotContain("SEQUENCE:2")
                .doesNotContain("DTSTAMP:20250101T000000Z");
        // The new lines go before the alarm, where RFC 5545 wants properties.
        assertThat(put.indexOf("SUMMARY:Jour fixe Team")).isLessThan(put.indexOf("BEGIN:VALARM"));
    }

    @Test
    void a_calendar_id_on_another_server_is_refused_before_the_password_leaves() throws IOException {
        try (FakeDav elsewhere = new FakeDav()) {
            dav.answers("<d:multistatus xmlns:d=\"DAV:\"/>", "<d:multistatus xmlns:d=\"DAV:\"/>");
            CalDavCalendarStore store = store("/dav/me/");
            String stolen = elsewhere.url("/collect/").toString();
            String networkPath = "//127.0.0.1:" + elsewhere.url("/").getPort() + "/collect/";

            for (String id : List.of(stolen, networkPath, "https://attacker.example/collect/",
                    "http://me%40web.de@127.0.0.1:" + dav.url("/").getPort() + "/dav/me/")) {
                assertThatThrownBy(() -> store.read(id, "erster"))
                        .isInstanceOf(CalendarStoreException.class)
                        .hasMessageContaining("not a calendar of this account");
                assertThatThrownBy(() -> store.events(id, Instant.parse("2026-09-23T00:00:00Z"),
                        Instant.parse("2026-09-24T00:00:00Z"), 10))
                        .isInstanceOf(CalendarStoreException.class);
            }
            // The same server, but above the account's address, is not the account's either.
            assertThatThrownBy(() -> store.delete(dav.url("/dav/someone-else/").toString(), "erster"))
                    .isInstanceOf(CalendarStoreException.class);
            assertThatThrownBy(() -> store.read(dav.url("/dav/me/../someone-else/").toString(), "erster"))
                    .isInstanceOf(CalendarStoreException.class);

            assertThat(elsewhere.calls()).isZero();
            // The account's own server was only asked for its list of calendars.
            for (int i = 0; i < dav.calls(); i++) assertThat(dav.call(i).method()).isEqualTo("PROPFIND");
        }
    }

    @Test
    void a_calendar_under_the_account_or_one_the_server_listed_is_followed() {
        String ical = "BEGIN:VCALENDAR\r\nBEGIN:VEVENT\r\nUID:erster\r\nSUMMARY:Ferien\r\n"
                + "DTSTART;VALUE=DATE:20260923\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n";
        dav.answer(200, ical, null)
                .answers("""
                        <d:multistatus xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav">
                          <d:response>
                            <d:href>/dav/shared/team/</d:href>
                            <d:propstat><d:prop>
                              <d:resourcetype><d:collection/><c:calendar/></d:resourcetype>
                              <d:displayname>Team</d:displayname>
                            </d:prop></d:propstat>
                          </d:response>
                        </d:multistatus>
                        """)
                .answer(200, ical, null);
        CalDavCalendarStore store = store("/dav/me/");

        // Under the account's address: followed without asking.
        assertThat(store.read("/dav/me/calendar/", "erster").title()).isEqualTo("Ferien");
        // Elsewhere on the server, but listed by it as one of the account's calendars.
        assertThat(store.read("/dav/shared/team/", "erster").title()).isEqualTo("Ferien");

        assertThat(dav.call(0).path()).isEqualTo("/dav/me/calendar/erster.ics");
        assertThat(dav.call(1).method()).isEqualTo("PROPFIND");
        assertThat(dav.call(2).path()).isEqualTo("/dav/shared/team/erster.ics");
    }

    @Test
    void a_redirect_to_another_server_is_not_followed_with_the_password() throws IOException {
        try (FakeDav elsewhere = new FakeDav()) {
            dav.redirects(302, elsewhere.url("/dav/me/").toString());

            assertThatThrownBy(() -> store("/dav/me/").calendars())
                    .isInstanceOf(CalendarStoreException.class)
                    .hasMessageContaining("not the server of this account");

            assertThat(elsewhere.calls()).isZero();
            assertThat(dav.calls()).isEqualTo(1);
        }
    }

    @Test
    void a_redirect_on_the_same_server_is_followed_with_method_and_body() {
        dav.redirects(301, "/dav/users/me/")
                .answers("""
                        <d:multistatus xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav">
                          <d:response>
                            <d:href>/dav/users/me/calendar/</d:href>
                            <d:propstat><d:prop>
                              <d:resourcetype><d:collection/><c:calendar/></d:resourcetype>
                              <d:displayname>Privat</d:displayname>
                            </d:prop></d:propstat>
                          </d:response>
                        </d:multistatus>
                        """);

        List<UserCalendar> calendars = store("/dav/me/").calendars();

        assertThat(calendars).extracting(UserCalendar::name).containsExactly("Privat");
        assertThat(dav.call(1).method()).isEqualTo("PROPFIND");
        assertThat(dav.call(1).path()).isEqualTo("/dav/users/me/");
        assertThat(dav.call(1).depth()).isEqualTo("1");
        assertThat(dav.call(1).authorization()).startsWith("Basic ");
        assertThat(dav.call(1).body()).contains("displayname");
    }

    @Test
    void a_redirect_loop_ends() {
        for (int i = 0; i <= CalDavClient.MAX_REDIRECTS; i++) dav.redirects(307, "/dav/me/");

        assertThatThrownBy(() -> store("/dav/me/").calendars())
                .isInstanceOf(CalendarStoreException.class)
                .hasMessageContaining("keeps redirecting");
        assertThat(dav.calls()).isEqualTo(CalDavClient.MAX_REDIRECTS + 1);
    }

    @Test
    void a_refused_sign_in_says_what_to_do_about_it() {
        dav.refuses(401);

        assertThatThrownBy(() -> store("/dav/me/").calendars())
                .isInstanceOf(CalendarStoreException.class)
                .hasMessageContaining("app-specific password")
                .hasMessageContaining("me@web.de");
    }

    @Test
    void the_card_takes_an_address_a_user_and_a_password() {
        assertThat(CalDavAccount.connectionSpec().provider()).isEqualTo("caldav");
        assertThat(CalDavAccount.connectionSpec().multiple()).isTrue();

        CalDavAccount account = CalDavAccount.from(connection(Map.of(
                "url", "https://caldav.web.de/begenda/dav/me@web.de/calendar/",
                "user", "me@web.de", "password", "secret")));
        assertThat(account.url().getHost()).isEqualTo("caldav.web.de");
        assertThat(account.authorization()).isEqualTo("Basic bWVAd2ViLmRlOnNlY3JldA==");

        assertThatThrownBy(() -> CalDavAccount.from(connection(Map.of("user", "me", "password", "p"))))
                .isInstanceOf(CalDavException.class).hasMessageContaining("address");
        assertThatThrownBy(() -> CalDavAccount.from(connection(Map.of(
                "url", "caldav.web.de", "user", "me", "password", "p"))))
                .isInstanceOf(CalDavException.class).hasMessageContaining("https://");
    }

    @Test
    void plain_http_is_only_accepted_for_a_server_nearby() {
        // Basic authentication without TLS hands the password to everyone on the way.
        assertThatThrownBy(() -> CalDavAccount.from(connection(Map.of(
                "url", "http://caldav.example.com/dav/", "user", "me", "password", "p"))))
                .isInstanceOf(CalDavException.class).hasMessageContaining("https://");
        assertThatThrownBy(() -> CalDavAccount.from(connection(Map.of(
                "url", "http://8.8.8.8/dav/", "user", "me", "password", "p"))))
                .isInstanceOf(CalDavException.class);

        for (String url : List.of("http://localhost:5232/", "http://127.0.0.1:5232/", "http://192.168.1.10/dav/",
                "http://10.0.0.5/", "http://[::1]:5232/", "http://nas:5232/", "http://radicale.local/",
                "https://caldav.example.com/dav/")) {
            assertThat(CalDavAccount.from(connection(Map.of("url", url, "user", "me", "password", "p"))).url())
                    .hasToString(url);
        }
    }

    private static ToolConnection connection(Map<String, String> fields) {
        return new ToolConnection() {
            @Override public String key() {
                return "web";
            }

            @Override public String label() {
                return "WEB.DE";
            }

            @Override public String provider() {
                return CalDavAccount.PROVIDER;
            }

            @Override public String value(String field) {
                return fields.get(field);
            }

            @Override public boolean usable() {
                return true;
            }
        };
    }
}
