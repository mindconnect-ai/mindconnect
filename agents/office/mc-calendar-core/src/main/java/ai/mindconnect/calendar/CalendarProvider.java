package ai.mindconnect.calendar;

import ai.mindconnect.agent.tool.ToolConnection;

/**
 * One kind of calendar account: CalDAV here, Microsoft Graph and Google
 * Calendar elsewhere.
 *
 * <p>The same seam the mail port has ({@code ai.mindconnect.mail.MailProvider}):
 * {@link CalendarAccounts} asks every provider it finds — through {@code
 * ServiceLoader}, or handed in — so a screen or a tool reads whatever
 * calendars the classpath can open, and a new kind is a module rather than a
 * branch in a switch.
 */
public interface CalendarProvider {

    /** The connection provider this opens — {@code caldav}, {@code microsoft}, {@code google}. */
    String provider();

    /** The calendars on that connection, opened for one request and closed by the caller. */
    CalendarStore open(ToolConnection connection);
}
