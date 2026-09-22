package ai.mindconnect.calendar.caldav;

import ai.mindconnect.agent.tool.ToolConnection;
import ai.mindconnect.calendar.CalendarEvent;
import ai.mindconnect.calendar.CalendarStoreException;
import ai.mindconnect.calendar.EventDraft;
import ai.mindconnect.calendar.EventTime;
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
    void changing_one_writes_the_whole_file_again_and_deleting_removes_it() {
        dav.answers("<d:multistatus xmlns:d=\"DAV:\"/>", "", "");

        CalDavCalendarStore store = store("/dav/me/calendar/");
        store.update(null, "erster", new EventDraft(null, "Ferien (verlängert)",
                EventTime.on(LocalDate.of(2026, 9, 23), LocalDate.of(2026, 9, 27)), null, null, List.of()));
        store.delete(null, "erster");

        assertThat(dav.call(1).method()).isEqualTo("PUT");
        assertThat(dav.call(1).path()).endsWith("/erster.ics");
        // An all-day event ends the morning after its last day.
        assertThat(dav.call(1).body()).contains("DTSTART;VALUE=DATE:20260923")
                .contains("DTEND;VALUE=DATE:20260928");
        assertThat(dav.call(2).method()).isEqualTo("DELETE");
        assertThat(dav.call(2).path()).endsWith("/erster.ics");
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
