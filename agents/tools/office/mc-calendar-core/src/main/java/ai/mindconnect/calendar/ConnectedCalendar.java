package ai.mindconnect.calendar;

/**
 * One calendar account the user has attached, named the way the screen
 * addresses it: {@code provider.key}, for example {@code microsoft.work}.
 *
 * <p>The same shape as the mail client's {@code ConnectedMailbox}, and
 * deliberately not shared with it: the two happen to agree today, and a common
 * supertype would tie the calendar's future to the mailbox's.
 */
public record ConnectedCalendar(String provider, String key, String label, boolean usable) {

    public String id() {
        return provider + "." + key;
    }

    /** "Work · Outlook" — what the user called the account, and what it is. */
    public String describe() {
        String kind = kind();
        return label == null || label.isBlank() ? kind : label + " · " + kind;
    }

    /** What the account is: "Outlook", "Google". */
    public String kind() {
        return switch (provider) {
            case "caldav" -> "CalDAV";
            case "microsoft" -> "Outlook";
            case "google" -> "Google";
            default -> provider;
        };
    }

    public static String providerOf(String id) {
        int dot = id == null ? -1 : id.indexOf('.');
        return dot <= 0 ? null : id.substring(0, dot);
    }

    public static String keyOf(String id) {
        int dot = id == null ? -1 : id.indexOf('.');
        return dot < 0 || dot == id.length() - 1 ? null : id.substring(dot + 1);
    }
}
