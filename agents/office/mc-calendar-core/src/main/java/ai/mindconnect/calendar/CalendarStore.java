package ai.mindconnect.calendar;

import java.time.Instant;
import java.util.List;

/**
 * One account's calendars, and what is in them.
 *
 * <p>The port the Office calendar screen is written against, so that the
 * screen never learns whether it is looking at Outlook or at Google: read a
 * period, put something new in, change or delete what is there.
 *
 * <p>A store is opened per request and closed by its caller; it holds no
 * credential of its own. See {@link CalendarAccounts}.
 */
public interface CalendarStore extends AutoCloseable {

    /** Every calendar in this account, the primary one first. */
    List<UserCalendar> calendars();

    /**
     * What is in {@code calendarId} between {@code from} and {@code to},
     * earliest first, with a recurring entry appearing as the occurrences
     * that fall inside the period rather than as the rule behind them.
     *
     * @param calendarId the calendar, or null for the account's primary one
     * @param limit      at most this many; a month of a busy calendar is
     *                   hundreds of entries and a screen shows a page of them
     */
    List<CalendarEvent> events(String calendarId, Instant from, Instant to, int limit);

    /** One entry, with the parts a listing leaves out — its notes, its attendees. */
    CalendarEvent read(String calendarId, String eventId);

    /** True when this account may create entries at all. */
    boolean canCreate();

    /**
     * Puts {@code draft} in a calendar and answers with the new entry's id.
     * Its reminders are written as {@link #reminders()} fits them; null ones
     * leave the entry with the calendar's default.
     */
    String create(EventDraft draft);

    /**
     * Replaces what {@code draft} says of an existing entry — title, time,
     * place, notes, attendees. The caller reads the entry first and changes
     * only what it means to; the store writes all of it — except the
     * reminders, which are replaced only when the draft has some (an empty
     * list removes them) and left as they are when it has null.
     */
    default void update(String calendarId, String eventId, EventDraft draft) {
        throw new CalendarStoreException("This calendar cannot change entries.");
    }

    /**
     * What this store does with {@link EventDraft#reminders()}: how many it
     * writes per entry, how far ahead, and whether extras are refused or
     * dropped. {@link ReminderSupport#NONE} — the default — writes none, and a
     * caller that was asked for one says so rather than claiming it was set.
     */
    default ReminderSupport reminders() {
        return ReminderSupport.NONE;
    }

    /** Removes an entry; for a meeting the user organised, the provider tells the attendees. */
    default void delete(String calendarId, String eventId) {
        throw new CalendarStoreException("This calendar cannot delete entries.");
    }

    @Override
    void close();
}
