package ai.mindconnect.calendar.caldav;

import ai.mindconnect.agent.tool.ToolConnection;
import ai.mindconnect.calendar.CalendarProvider;
import ai.mindconnect.calendar.CalendarStore;
import ai.mindconnect.calendar.CalendarStoreException;

/**
 * CalDAV as a kind of calendar account — the one every self-hosted calendar
 * and most mail providers speak. Found by {@code ServiceLoader}.
 */
public class CalDavCalendarProvider implements CalendarProvider {

    private final CalDavClient dav;

    public CalDavCalendarProvider() {
        this(new CalDavClient());
    }

    CalDavCalendarProvider(CalDavClient dav) {
        this.dav = dav;
    }

    @Override
    public String provider() {
        return CalDavAccount.PROVIDER;
    }

    @Override
    public CalendarStore open(ToolConnection connection) {
        try {
            return new CalDavCalendarStore(dav, CalDavAccount.from(connection));
        } catch (CalDavException e) {
            throw new CalendarStoreException(e.getMessage(), e);
        }
    }
}
