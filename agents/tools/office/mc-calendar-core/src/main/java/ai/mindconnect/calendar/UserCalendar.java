package ai.mindconnect.calendar;

/**
 * One calendar inside one account: the work one, the shared team one, the
 * German holidays somebody subscribed to years ago.
 *
 * @param id       the provider's own id, opaque to everything above
 * @param name     what the user called it
 * @param colour   a CSS colour the provider suggested, or null when it suggested none
 * @param primary  true for the account's default calendar
 * @param writable true when this account may put something in it — a
 *                 subscribed holiday calendar is readable and not writable,
 *                 and a New button that fails afterwards is worse than one
 *                 that is not offered
 */
public record UserCalendar(String id, String name, String colour, boolean primary, boolean writable) {

    public static UserCalendar of(String id, String name) {
        return new UserCalendar(id, name, null, false, true);
    }
}
