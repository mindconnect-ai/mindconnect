package ai.mindconnect.calendar.caldav;

import ai.mindconnect.agent.tool.AgentTool;
import ai.mindconnect.agent.tool.ConnectionSpec;
import ai.mindconnect.agent.tool.ConnectionTest;
import ai.mindconnect.agent.tool.ConnectionTester;
import ai.mindconnect.agent.tool.MultiToolProvider;
import ai.mindconnect.agent.tool.Tool;
import ai.mindconnect.agent.tool.ToolCallScope;
import ai.mindconnect.calendar.UserCalendar;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The CalDAV card on a user's profile, and the Test button behind it.
 *
 * <p>No tools of its own: the calendar tools work on every kind of calendar
 * account at once ({@code mc-agent-tools-calendar}). A provider with no tool
 * names still declares its connection, which is what puts the card there.
 */
public class CalDavConnectionProvider implements MultiToolProvider {

    private final CalDavClient dav;

    public CalDavConnectionProvider() {
        this(new CalDavClient());
    }

    CalDavConnectionProvider(CalDavClient dav) {
        this.dav = dav;
    }

    @Override public String group() { return "calendar"; }

    @Override public Set<String> toolNames() { return Set.of(); }

    @Override public ConnectionSpec connectionSpec() { return CalDavAccount.connectionSpec(); }

    /**
     * Signs in and asks what calendars are there — the one check that proves
     * address, user and password together, and says what was found.
     */
    @Override
    public Optional<ConnectionTester> connectionTester() {
        return Optional.of(connection -> {
            try {
                CalDavAccount account = CalDavAccount.from(connection);
                List<UserCalendar> calendars =
                        new CalDavCalendarStore(dav, account).calendars();
                String names = calendars.stream().map(UserCalendar::name).limit(5)
                        .reduce((a, b) -> a + ", " + b).orElse("");
                return ConnectionTest.ok(calendars.size() == 1
                        ? "One calendar: " + names
                        : calendars.size() + " calendars: " + names, account.user());
            } catch (CalDavException e) {
                return ConnectionTest.failed(e.getMessage());
            } catch (RuntimeException e) {
                return ConnectionTest.failed("The calendar server did not answer as a CalDAV server: "
                        + e.getMessage());
            }
        });
    }

    @Override
    public Optional<Tool> create(String toolName, AgentTool agentTool, ToolCallScope scope) {
        return Optional.empty();
    }
}
