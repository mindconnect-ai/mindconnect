package ai.mindconnect.calendar;

import java.util.List;

/**
 * What one kind of calendar can keep of the {@link Reminders} asked for: how
 * many per entry, how far ahead, and what happens to more than that.
 *
 * <p>The providers differ in a way a caller has to hear about. Google keeps
 * up to five per entry and refuses a sixth, so asking for six is an error the
 * model can correct. Outlook keeps exactly one, so asking for two is not an
 * error — the one closest to the start is kept, and the caller says the other
 * was dropped. A store that does not write reminders at all says {@link
 * #NONE}, and whatever it is handed is not written.
 *
 * @param max        reminders per entry; 0 when the store writes none
 * @param maxMinutes how far before the start a reminder may be
 * @param keepsOne   true when more than {@code max} are not refused but
 *                   cut down to the one closest to the start ({@code max} is 1)
 */
public record ReminderSupport(int max, int maxMinutes, boolean keepsOne) {

    /** The store writes no reminders: what is asked for is left out. The default of {@link CalendarStore}. */
    public static final ReminderSupport NONE = new ReminderSupport(0, 0, false);

    /** As many as the format holds, as far ahead as asked — iCalendar. */
    public static final ReminderSupport ANY = new ReminderSupport(Integer.MAX_VALUE, Integer.MAX_VALUE, false);

    public ReminderSupport {
        if (max < 0 || maxMinutes < 0) throw new IllegalArgumentException("Limits are 0 or more");
        if (keepsOne && max != 1) throw new IllegalArgumentException("keepsOne means one reminder per entry");
    }

    /** Up to {@code max} per entry, up to {@code maxMinutes} ahead; more is refused. */
    public static ReminderSupport upTo(int max, int maxMinutes) {
        return new ReminderSupport(max, maxMinutes, false);
    }

    /** One per entry; of several asked for, the one closest to the start is kept and the rest dropped. */
    public static ReminderSupport onlyOne(int maxMinutes) {
        return new ReminderSupport(1, maxMinutes, true);
    }

    /** True when this store writes reminders at all. */
    public boolean writes() {
        return max > 0;
    }

    /**
     * What of {@code asked} this calendar keeps.
     *
     * <p>Null stays null — reminders left alone need no room. An empty list
     * fits everywhere that writes reminders.
     *
     * @throws CalendarStoreException when the store writes none, when one is
     *         further ahead than {@link #maxMinutes}, or when there are more
     *         than {@link #max} and the extras are not simply dropped — in
     *         words the model can act on
     */
    public Fit fit(List<Integer> asked) {
        List<Integer> minutes = Reminders.of(asked);
        if (minutes == null) return new Fit(null, List.of());
        if (!writes()) throw new CalendarStoreException("This calendar does not take reminders.");
        for (int m : minutes) {
            if (m > maxMinutes) {
                throw new CalendarStoreException("A reminder here can be at most " + maxMinutes
                        + " minutes (" + Reminders.span(maxMinutes) + ") before the start, not " + m + ".");
            }
        }
        if (minutes.size() <= max) return new Fit(minutes, List.of());
        if (keepsOne) return new Fit(List.of(minutes.get(0)), List.copyOf(minutes.subList(1, minutes.size())));
        throw new CalendarStoreException("This calendar keeps at most " + max + " reminder"
                + (max == 1 ? "" : "s") + " per entry, not " + minutes.size() + ".");
    }

    /**
     * What a store writes, and what it had to leave out.
     *
     * @param kept    the minutes to write, shortest first — null to leave the reminders alone
     * @param dropped the ones that did not fit, for the caller to mention
     */
    public record Fit(List<Integer> kept, List<Integer> dropped) {

        public Fit {
            dropped = dropped == null ? List.of() : List.copyOf(dropped);
        }
    }
}
