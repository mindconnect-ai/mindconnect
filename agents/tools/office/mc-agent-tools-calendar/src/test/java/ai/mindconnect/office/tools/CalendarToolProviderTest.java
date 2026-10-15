package ai.mindconnect.office.tools;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** One family of calendar tools for every kind of account, and every change a tool of its own. */
class CalendarToolProviderTest {

    @Test
    void the_names_are_the_same_whatever_the_account_is() {
        CalendarToolProvider provider = new CalendarToolProvider();

        assertThat(provider.group()).isEqualTo("office");
        assertThat(provider.subgroup("calendar_events")).isEqualTo("Calendar");
        assertThat(provider.toolNames()).containsExactlyInAnyOrder("calendar_calendars", "calendar_events",
                        "calendar_read", "calendar_create", "calendar_update", "calendar_delete")
                // Nothing names a provider: CalDAV, Outlook and Google are the same six tools.
                .noneMatch(n -> n.startsWith("gcalendar_") || n.startsWith("outlook_"));
        // Nothing bound: nothing offered.
        assertThat(provider.isAvailable()).isFalse();
        assertThat(provider.create("calendar_events", null, null)).isEmpty();
    }
}
