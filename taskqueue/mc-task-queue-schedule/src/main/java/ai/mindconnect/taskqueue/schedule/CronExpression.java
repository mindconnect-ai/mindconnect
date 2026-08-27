package ai.mindconnect.taskqueue.schedule;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.BitSet;
import java.util.Locale;
import java.util.Optional;

/**
 * A cron expression and the one question worth asking it: when does this fire
 * next? Hand-written rather than pulled in as a dependency — the queue's core
 * has none, and this is the only thing the scheduler needs from cron.
 *
 * <p>Five fields ({@code minute hour day-of-month month day-of-week}) or six
 * with seconds in front, the way Spring writes them:
 *
 * <pre>
 *   0 3 * * *          03:00 every day
 *   0 3 * * MON-FRI    03:00 on weekdays
 *   0/15 * * * *       every 15 minutes, starting on the hour
 *   0 0 1 * *          midnight on the 1st
 *   30 0 3 * * *       03:00:30 every day (six fields — seconds first)
 * </pre>
 *
 * Each field takes a star, a number, a name ({@code JAN}, {@code MON}), a
 * range {@code a-b}, a step ({@code a-b/n}, or {@code a/n} from a starting
 * point) and comma-separated lists of those. {@code ?} means the same as a
 * star. Day-of-week counts
 * {@code SUN}=0..{@code SAT}=6, with 7 accepted for Sunday too.
 *
 * <p><b>Day-of-month and day-of-week are OR'd</b> when both are restricted —
 * the classic cron rule, which is why {@code 0 0 1 * MON} means "the 1st AND
 * every Monday", not "a Monday that is the 1st".
 *
 * <p>Shortcuts: {@code @yearly}, {@code @annually}, {@code @monthly},
 * {@code @weekly}, {@code @daily}, {@code @midnight}, {@code @hourly}.
 *
 * <p>Time zones are the caller's ({@link #nextAfter(Instant, ZoneId)}) and
 * daylight saving is resolved by {@code java.time}: a firing in a gap moves to
 * the first valid instant after it, one in an overlap takes the earlier offset.
 */
public final class CronExpression {

    private static final String[] MONTH_NAMES = {
            "JAN", "FEB", "MAR", "APR", "MAY", "JUN", "JUL", "AUG", "SEP", "OCT", "NOV", "DEC"};
    private static final String[] DAY_NAMES = {"SUN", "MON", "TUE", "WED", "THU", "FRI", "SAT"};

    /** Nothing recurs beyond this — a 29 February expression needs four years. */
    private static final int SEARCH_YEARS = 4;

    /**
     * How many candidate firings may be discarded for landing before the
     * instant we started from. Only a daylight-saving overlap does that, and
     * an overlap is an hour — generous even for a per-second expression.
     */
    private static final int OVERLAP_STEPS = 4_096;

    private final BitSet seconds;
    private final BitSet minutes;
    private final BitSet hours;
    private final BitSet daysOfMonth;
    private final BitSet months;
    private final BitSet daysOfWeek;
    private final boolean domRestricted;
    private final boolean dowRestricted;
    private final String source;

    private CronExpression(BitSet seconds, BitSet minutes, BitSet hours, BitSet daysOfMonth,
                           BitSet months, BitSet daysOfWeek,
                           boolean domRestricted, boolean dowRestricted, String source) {
        this.seconds = seconds;
        this.minutes = minutes;
        this.hours = hours;
        this.daysOfMonth = daysOfMonth;
        this.months = months;
        this.daysOfWeek = daysOfWeek;
        this.domRestricted = domRestricted;
        this.dowRestricted = dowRestricted;
        this.source = source;
    }

    /**
     * @throws IllegalArgumentException on anything this does not understand —
     *         loudly and at parse time, because a schedule that silently never
     *         fires is the worst possible failure mode for a scheduler
     */
    public static CronExpression parse(String expression) {
        if (expression == null || expression.isBlank()) {
            throw new IllegalArgumentException("Cron expression is empty");
        }
        String expanded = expandMacro(expression.trim());
        String[] fields = expanded.split("\\s+");
        if (fields.length != 5 && fields.length != 6) {
            throw new IllegalArgumentException(
                    "Cron expression must have 5 fields (minute hour day-of-month month day-of-week) "
                            + "or 6 with leading seconds, got " + fields.length + ": " + expression);
        }
        int offset = fields.length == 6 ? 1 : 0;
        BitSet seconds = offset == 1 ? parseField(fields[0], 0, 59, null, 0, expression) : only(0);
        String dom = fields[offset + 2];
        String dow = fields[offset + 4];
        return new CronExpression(
                seconds,
                parseField(fields[offset], 0, 59, null, 0, expression),
                parseField(fields[offset + 1], 0, 23, null, 0, expression),
                parseField(dom, 1, 31, null, 0, expression),
                parseField(fields[offset + 3], 1, 12, MONTH_NAMES, 1, expression),
                parseDaysOfWeek(dow, expression),
                restricted(dom),
                restricted(dow),
                expression.trim());
    }

    /**
     * The next firing strictly after {@code from}, empty if there is none
     * within four years.
     *
     * <p>Matching happens on wall-clock time, because that is what a cron
     * expression is about, and the zone is applied afterwards. A firing that
     * falls into a daylight-saving gap therefore still happens — moved forward
     * by the length of the gap, the {@code java.time} rule — instead of being
     * silently skipped for the year. In an overlap the earlier offset wins, so
     * it fires once rather than twice.
     */
    public Optional<Instant> nextAfter(Instant from, ZoneId zone) {
        LocalDateTime local = LocalDateTime.ofInstant(from, zone);
        for (int guard = 0; guard < OVERLAP_STEPS; guard++) {
            Optional<LocalDateTime> next = nextAfter(local);
            if (next.isEmpty()) return Optional.empty();
            Instant candidate = next.get().atZone(zone).toInstant();
            // Inside an autumn overlap the same wall-clock time maps to an
            // instant we have already passed — keep walking rather than
            // handing back a firing in the past.
            if (candidate.isAfter(from)) return Optional.of(candidate);
            local = next.get();
        }
        return Optional.empty();
    }

    /** {@link #nextAfter(Instant, ZoneId)} for a caller that already has a zoned time. */
    public Optional<ZonedDateTime> nextAfter(ZonedDateTime from) {
        return nextAfter(from.toInstant(), from.getZone()).map(i -> i.atZone(from.getZone()));
    }

    /**
     * The next matching wall-clock time strictly after {@code from}. Walks by
     * whole fields — a month that cannot match skips a month, not a second —
     * so even a "29 February" expression is found in a few dozen steps.
     */
    public Optional<LocalDateTime> nextAfter(LocalDateTime from) {
        LocalDateTime limit = from.plusYears(SEARCH_YEARS);
        LocalDateTime t = from.withNano(0).plusSeconds(1);
        while (t.isBefore(limit)) {
            if (!months.get(t.getMonthValue())) {
                t = t.toLocalDate().withDayOfMonth(1).plusMonths(1).atStartOfDay();
                continue;
            }
            if (!dayMatches(t)) {
                t = t.toLocalDate().plusDays(1).atStartOfDay();
                continue;
            }
            if (!hours.get(t.getHour())) {
                t = t.plusHours(1).withMinute(0).withSecond(0);
                continue;
            }
            if (!minutes.get(t.getMinute())) {
                t = t.plusMinutes(1).withSecond(0);
                continue;
            }
            if (!seconds.get(t.getSecond())) {
                t = t.plusSeconds(1);
                continue;
            }
            return Optional.of(t);
        }
        return Optional.empty();
    }

    @Override
    public String toString() {
        return source;
    }

    // ── matching ────────────────────────────────────────────────────────────

    private boolean dayMatches(LocalDateTime t) {
        boolean dom = daysOfMonth.get(t.getDayOfMonth());
        boolean dow = daysOfWeek.get(t.getDayOfWeek().getValue() % 7);   // ISO MON=1..SUN=7 → SUN=0
        // The classic cron rule: two restricted day fields are alternatives,
        // not conditions. Only one restricted field narrows anything.
        return domRestricted && dowRestricted ? dom || dow : dom && dow;
    }

    // ── parsing ─────────────────────────────────────────────────────────────

    private static String expandMacro(String expression) {
        return switch (expression.toLowerCase(Locale.ROOT)) {
            case "@yearly", "@annually" -> "0 0 1 1 *";
            case "@monthly" -> "0 0 1 * *";
            case "@weekly" -> "0 0 * * 0";
            case "@daily", "@midnight" -> "0 0 * * *";
            case "@hourly" -> "0 * * * *";
            default -> expression;
        };
    }

    private static boolean restricted(String field) {
        return !field.equals("*") && !field.equals("?");
    }

    private static BitSet only(int value) {
        BitSet bits = new BitSet();
        bits.set(value);
        return bits;
    }

    /** Days of week are parsed one wider so a literal 7 can fold onto Sunday. */
    private static BitSet parseDaysOfWeek(String field, String source) {
        BitSet bits = parseField(field, 0, 7, DAY_NAMES, 0, source);
        if (bits.get(7)) {
            bits.set(0);
            bits.clear(7);
        }
        return bits;
    }

    /**
     * @param names    accepted names for this field, or null for numbers only
     * @param nameBase what {@code names[0]} stands for — 1 for JAN, 0 for SUN
     */
    private static BitSet parseField(String field, int min, int max,
                                     String[] names, int nameBase, String source) {
        BitSet bits = new BitSet(max + 1);
        String normalized = field.equals("?") ? "*" : field;
        for (String part : normalized.split(",")) {
            if (part.isBlank()) throw fail(field, source, "empty list entry");
            int step = 1;
            boolean stepped = false;
            int slash = part.indexOf('/');
            if (slash >= 0) {
                stepped = true;
                step = parsePositive(part.substring(slash + 1), field, source);
                part = part.substring(0, slash);
            }
            int from;
            int to;
            if (part.equals("*")) {
                from = min;
                to = max;
            } else {
                int dash = part.indexOf('-');
                if (dash > 0) {
                    from = value(part.substring(0, dash), names, nameBase, min, max, field, source);
                    to = value(part.substring(dash + 1), names, nameBase, min, max, field, source);
                } else {
                    from = value(part, names, nameBase, min, max, field, source);
                    // "5/10" is "from 5 onwards, every 10" — a lone value with a
                    // step is an open range, not a single point.
                    to = stepped ? max : from;
                }
            }
            if (from > to) throw fail(field, source, "range " + from + "-" + to + " runs backwards");
            for (int i = from; i <= to; i += step) bits.set(i);
        }
        if (bits.isEmpty()) throw fail(field, source, "matches nothing");
        return bits;
    }

    private static int parsePositive(String text, String field, String source) {
        try {
            int step = Integer.parseInt(text);
            if (step < 1) throw fail(field, source, "step must be at least 1");
            return step;
        } catch (NumberFormatException e) {
            throw fail(field, source, "step '" + text + "' is not a number");
        }
    }

    private static int value(String text, String[] names, int nameBase,
                             int min, int max, String field, String source) {
        if (names != null) {
            for (int i = 0; i < names.length; i++) {
                if (names[i].equalsIgnoreCase(text)) return i + nameBase;
            }
        }
        int value;
        try {
            value = Integer.parseInt(text);
        } catch (NumberFormatException e) {
            throw fail(field, source, "'" + text + "' is not a number"
                    + (names != null ? " or a known name" : ""));
        }
        if (value < min || value > max) {
            throw fail(field, source, value + " is outside " + min + "-" + max);
        }
        return value;
    }

    private static IllegalArgumentException fail(String field, String source, String why) {
        return new IllegalArgumentException(
                "Cron field '" + field + "' in \"" + source + "\": " + why);
    }
}
