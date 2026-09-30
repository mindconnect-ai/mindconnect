package ai.mindconnect.calendar;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/**
 * An entry's reminders: minutes before it starts, and nothing else.
 *
 * <p>Every provider says it differently — Google as a list of overrides with a
 * method each, Outlook as one switch and one number, iCalendar as a
 * {@code VALARM} per alarm — and all of them mean "tell me this long before".
 * So that is the one notion here: {@code [10, 60]} is a reminder ten minutes
 * and one an hour before the start. For an all-day entry the start is its
 * first midnight. How a reminder reaches the person (a popup, a sound, a
 * mail) is the provider's business; a store writes the popup kind where it
 * has to choose.
 *
 * <p>Three states, and they are different: a list with minutes in it, an
 * empty list — no reminder at all — and {@code null}, which leaves the
 * reminders alone: a new entry gets whatever the calendar gives new entries,
 * a changed one keeps what it has.
 */
public final class Reminders {

    private Reminders() { }

    /**
     * {@code minutes} as the port keeps them: each once, the shortest first,
     * unmodifiable — or null when null was given.
     *
     * @throws IllegalArgumentException for a reminder after the start or a missing one
     */
    public static List<Integer> of(List<Integer> minutes) {
        if (minutes == null) return null;
        TreeSet<Integer> sorted = new TreeSet<>();
        for (Integer m : minutes) {
            if (m == null || m < 0) {
                throw new IllegalArgumentException("A reminder is a number of minutes before the start, "
                        + "0 or more — not " + m + ".");
            }
            sorted.add(m);
        }
        return List.copyOf(sorted);
    }

    /**
     * "10 minutes and 1 hour before" — for a tool result or a screen.
     * "no reminder" for an empty list, and null for null: there is nothing to
     * say about reminders nobody knows.
     */
    public static String describe(List<Integer> minutes) {
        if (minutes == null) return null;
        if (minutes.isEmpty()) return "no reminder";
        List<String> parts = new ArrayList<>();
        boolean atStart = false;
        for (int m : minutes) {
            if (m == 0) atStart = true;
            else parts.add(span(m));
        }
        String before = parts.isEmpty() ? "" : join(parts) + " before";
        if (!atStart) return before;
        return before.isEmpty() ? "at the start" : "at the start and " + before;
    }

    /** 90 → "90 minutes", 60 → "1 hour", 2880 → "2 days", 10080 → "1 week". */
    static String span(int minutes) {
        if (minutes % 10_080 == 0) return plural(minutes / 10_080, "week");
        if (minutes % 1_440 == 0) return plural(minutes / 1_440, "day");
        if (minutes % 60 == 0) return plural(minutes / 60, "hour");
        return plural(minutes, "minute");
    }

    private static String plural(int n, String unit) {
        return n + " " + unit + (n == 1 ? "" : "s");
    }

    private static String join(List<String> parts) {
        if (parts.size() == 1) return parts.get(0);
        return String.join(", ", parts.subList(0, parts.size() - 1)) + " and " + parts.get(parts.size() - 1);
    }
}
