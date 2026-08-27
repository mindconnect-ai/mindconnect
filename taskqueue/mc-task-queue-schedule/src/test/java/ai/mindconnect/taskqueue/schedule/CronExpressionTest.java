package ai.mindconnect.taskqueue.schedule;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CronExpressionTest {

    private static final ZoneId UTC = ZoneId.of("UTC");
    private static final ZoneId ZURICH = ZoneId.of("Europe/Zurich");

    private static Instant at(String isoUtc) {
        return Instant.parse(isoUtc);
    }

    private static String next(String cron, String fromUtc) {
        return CronExpression.parse(cron).nextAfter(at(fromUtc), UTC).orElseThrow().toString();
    }

    @Test
    void everyMinuteFiresOnTheNextMinuteBoundary() {
        assertThat(next("* * * * *", "2026-03-01T10:17:42Z")).isEqualTo("2026-03-01T10:18:00Z");
    }

    @Test
    void aDailyTimeSkipsToTheNextDayWhenTodayIsPast() {
        assertThat(next("0 3 * * *", "2026-03-01T02:59:59Z")).isEqualTo("2026-03-01T03:00:00Z");
        assertThat(next("0 3 * * *", "2026-03-01T03:00:00Z")).isEqualTo("2026-03-02T03:00:00Z");
    }

    @Test
    void stepsAndListsAndRanges() {
        assertThat(next("0/15 * * * *", "2026-03-01T10:01:00Z")).isEqualTo("2026-03-01T10:15:00Z");
        assertThat(next("5,35 * * * *", "2026-03-01T10:10:00Z")).isEqualTo("2026-03-01T10:35:00Z");
        assertThat(next("0 9-17 * * *", "2026-03-01T20:00:00Z")).isEqualTo("2026-03-02T09:00:00Z");
        assertThat(next("0 0-23/6 * * *", "2026-03-01T01:00:00Z")).isEqualTo("2026-03-01T06:00:00Z");
    }

    @Test
    void namesForMonthsAndWeekdays() {
        // Monday 2026-03-02 is the first weekday after Sunday 2026-03-01.
        assertThat(next("0 8 * * MON-FRI", "2026-03-01T12:00:00Z")).isEqualTo("2026-03-02T08:00:00Z");
        assertThat(next("0 0 1 JAN *", "2026-03-01T00:00:00Z")).isEqualTo("2027-01-01T00:00:00Z");
    }

    @Test
    void sundayIsBothZeroAndSeven() {
        assertThat(next("0 0 * * 0", "2026-03-02T00:00:00Z"))
                .isEqualTo(next("0 0 * * 7", "2026-03-02T00:00:00Z"))
                .isEqualTo("2026-03-08T00:00:00Z");
    }

    @Test
    void secondsFieldMakesItSixFields() {
        assertThat(next("30 0 3 * * *", "2026-03-01T00:00:00Z")).isEqualTo("2026-03-01T03:00:30Z");
        assertThat(next("*/1 * * * * *", "2026-03-01T10:00:00Z")).isEqualTo("2026-03-01T10:00:01Z");
    }

    @Test
    void dayOfMonthAndDayOfWeekAreAlternativesWhenBothAreRestricted() {
        // "the 1st OR any Monday" — the classic cron rule, not an AND.
        CronExpression cron = CronExpression.parse("0 0 1 * MON");
        // Sunday 2026-03-01 → Monday the 2nd qualifies through the weekday half.
        assertThat(cron.nextAfter(at("2026-03-01T01:00:00Z"), UTC).orElseThrow())
                .isEqualTo(at("2026-03-02T00:00:00Z"));
        // Wednesday 2026-04-01 qualifies through the day-of-month half.
        assertThat(cron.nextAfter(at("2026-03-31T00:00:00Z"), UTC).orElseThrow())
                .isEqualTo(at("2026-04-01T00:00:00Z"));
    }

    @Test
    void onlyOneRestrictedDayFieldNarrows() {
        // Day-of-week is a star, so the 15th alone decides.
        assertThat(next("0 0 15 * *", "2026-03-16T00:00:00Z")).isEqualTo("2026-04-15T00:00:00Z");
    }

    @Test
    void aLeapDayIsFoundFourYearsOut() {
        assertThat(next("0 0 29 2 *", "2025-01-01T00:00:00Z")).isEqualTo("2028-02-29T00:00:00Z");
    }

    @Test
    void anImpossibleDateHasNoNextFiring() {
        // 30 February: parses fine, matches never.
        assertThat(CronExpression.parse("0 0 30 2 *").nextAfter(at("2026-01-01T00:00:00Z"), UTC))
                .isEmpty();
    }

    @Test
    void macrosExpand() {
        assertThat(next("@daily", "2026-03-01T05:00:00Z")).isEqualTo("2026-03-02T00:00:00Z");
        assertThat(next("@hourly", "2026-03-01T05:30:00Z")).isEqualTo("2026-03-01T06:00:00Z");
        assertThat(next("@monthly", "2026-03-05T00:00:00Z")).isEqualTo("2026-04-01T00:00:00Z");
        assertThat(next("@yearly", "2026-03-05T00:00:00Z")).isEqualTo("2027-01-01T00:00:00Z");
    }

    @Test
    void theZoneIsPartOfTheAnswer() {
        // "03:00 Zurich" is 02:00 UTC in winter and 01:00 UTC in summer — the
        // reason a schedule carries a zone instead of assuming UTC.
        CronExpression cron = CronExpression.parse("0 3 * * *");
        assertThat(cron.nextAfter(at("2026-01-15T00:00:00Z"), ZURICH).orElseThrow())
                .isEqualTo(at("2026-01-15T02:00:00Z"));
        assertThat(cron.nextAfter(at("2026-07-15T00:00:00Z"), ZURICH).orElseThrow())
                .isEqualTo(at("2026-07-15T01:00:00Z"));
    }

    @Test
    void aFiringInsideTheSpringDstGapStillHappens() {
        // Zurich jumps 02:00 → 03:00 on 2026-03-29, so local 02:30 does not
        // exist that day. It must not silently be skipped for the year: the
        // firing moves forward by the gap, to 03:30 local = 01:30 UTC.
        ZonedDateTime from = ZonedDateTime.of(2026, 3, 29, 0, 0, 0, 0, ZURICH);
        ZonedDateTime fired = CronExpression.parse("30 2 * * *").nextAfter(from).orElseThrow();
        assertThat(fired.toInstant()).isEqualTo(at("2026-03-29T01:30:00Z"));
    }

    @Test
    void aFiringInsideTheAutumnDstOverlapHappensOnceNotTwice() {
        // Zurich repeats 02:00–03:00 on 2026-10-25. A daily 02:30 fires at the
        // first pass (CEST) and then not again until the next day.
        CronExpression cron = CronExpression.parse("30 2 * * *");
        Instant first = cron.nextAfter(at("2026-10-25T00:00:00Z"), ZURICH).orElseThrow();
        assertThat(first).isEqualTo(at("2026-10-25T00:30:00Z"));               // 02:30 CEST
        assertThat(cron.nextAfter(first, ZURICH).orElseThrow())
                .isEqualTo(at("2026-10-26T01:30:00Z"));                        // 02:30 CET, next day
    }

    @Test
    void questionMarkMeansUnrestricted() {
        assertThat(next("0 0 ? * MON", "2026-03-01T00:00:00Z")).isEqualTo("2026-03-02T00:00:00Z");
    }

    @Test
    void brokenExpressionsFailAtParseTimeNotAtFireTime() {
        assertThatThrownBy(() -> CronExpression.parse("0 0 * *"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("5 fields");
        assertThatThrownBy(() -> CronExpression.parse("0 99 * * *"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("outside 0-23");
        assertThatThrownBy(() -> CronExpression.parse("0 0 * * FUNDAY"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("FUNDAY");
        assertThatThrownBy(() -> CronExpression.parse("0 17-9 * * *"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("backwards");
        assertThatThrownBy(() -> CronExpression.parse("0 0/0 * * *"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("at least 1");
        assertThatThrownBy(() -> CronExpression.parse(" "))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
