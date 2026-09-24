package ai.mindconnect.llm.domain;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LlmPriceTest {

    static LlmPrice price(String from, String to, String input, String output, String cached) {
        return LlmPrice.of("claude", LocalDate.parse(from), to == null ? null : LocalDate.parse(to), null,
                new BigDecimal(input), new BigDecimal(output), cached == null ? null : new BigDecimal(cached));
    }

    @Nested
    class Validity {

        final LlmPrice h1 = price("2026-01-01", "2026-07-01", "3", "15", null);
        final LlmPrice open = price("2026-07-01", null, "2", "10", null);

        @Test
        void fromIsInclusive() {
            assertThat(h1.isValidAt(Instant.parse("2026-01-01T00:00:00Z"))).isTrue();
            assertThat(h1.isValidAt(Instant.parse("2025-12-31T23:59:59.999Z"))).isFalse();
        }

        @Test
        void toIsExclusive() {
            assertThat(h1.isValidAt(Instant.parse("2026-06-30T23:59:59.999Z"))).isTrue();
            assertThat(h1.isValidAt(Instant.parse("2026-07-01T00:00:00Z"))).isFalse();
        }

        @Test
        void noEndMeansOpenEnded() {
            assertThat(open.isValidAt(Instant.parse("2126-01-01T00:00:00Z"))).isTrue();
            assertThat(open.isValidAt(Instant.parse("2026-06-30T12:00:00Z"))).isFalse();
        }

        @Test
        void aMomentIsReadAsItsUtcDay() {
            // 23:30 in UTC on June 30th is already July 1st in Berlin — the UTC day decides.
            assertThat(LlmPrices.priceAt(List.of(h1, open), "claude", Instant.parse("2026-06-30T23:30:00Z")))
                    .contains(h1);
        }

        @Test
        void priceAtPicksThePeriodOfTheDayAndOnlyOfThatConfig() {
            LlmPrice gpt = LlmPrice.of("gpt", LocalDate.parse("2020-01-01"), null, "USD",
                    BigDecimal.ONE, BigDecimal.TEN, null);
            List<LlmPrice> all = List.of(h1, open, gpt);

            assertThat(LlmPrices.priceAt(all, "claude", Instant.parse("2026-03-01T10:00:00Z"))).contains(h1);
            assertThat(LlmPrices.priceAt(all, "claude", Instant.parse("2026-07-01T00:00:00Z"))).contains(open);
            assertThat(LlmPrices.priceAt(all, "claude", Instant.parse("2025-03-01T10:00:00Z"))).isEmpty();
            assertThat(LlmPrices.priceAt(all, "nobody", Instant.parse("2026-03-01T10:00:00Z"))).isEmpty();
        }
    }

    @Nested
    class Overlap {

        @Test
        void periodsThatMeetDoNotOverlap() {
            assertThat(price("2026-01-01", "2026-07-01", "1", "1", null)
                    .overlaps(price("2026-07-01", null, "1", "1", null))).isFalse();
            assertThat(price("2026-07-01", null, "1", "1", null)
                    .overlaps(price("2026-01-01", "2026-07-01", "1", "1", null))).isFalse();
        }

        @Test
        void sharingOneDayIsAnOverlap() {
            assertThat(price("2026-01-01", "2026-07-02", "1", "1", null)
                    .overlaps(price("2026-07-01", null, "1", "1", null))).isTrue();
        }

        @Test
        void twoOpenEndedPeriodsAlwaysOverlap() {
            assertThat(price("2026-01-01", null, "1", "1", null)
                    .overlaps(price("2027-01-01", null, "1", "1", null))).isTrue();
        }

        @Test
        void oneInsideTheOtherOverlaps() {
            assertThat(price("2026-01-01", "2026-12-31", "1", "1", null)
                    .overlaps(price("2026-03-01", "2026-04-01", "1", "1", null))).isTrue();
        }

        @Test
        void anEditIsCheckedAgainstTheOthersNotItself() {
            LlmPrice stored = price("2026-01-01", null, "1", "1", null);
            LlmPrice edited = new LlmPrice(stored.id(), "claude", LocalDate.parse("2026-02-01"), null, "USD",
                    BigDecimal.ONE, BigDecimal.ONE, null);
            LlmPrice otherConfig = LlmPrice.of("gpt", LocalDate.parse("2026-01-01"), null, "USD",
                    BigDecimal.ONE, BigDecimal.ONE, null);

            assertThat(LlmPrices.overlapping(List.of(stored, otherConfig), edited)).isEmpty();
            assertThat(LlmPrices.overlapping(List.of(stored), price("2026-05-01", null, "1", "1", null)))
                    .containsExactly(stored);
        }
    }

    @Nested
    class Cost {

        @Test
        void ratesArePerMillionTokens() {
            LlmPrice p = price("2026-01-01", null, "3", "15", null);
            // 1,000 in at $3/M + 500 out at $15/M = 0.003 + 0.0075
            assertThat(p.cost(1_000, 0, 500)).isEqualByComparingTo("0.0105");
        }

        @Test
        void cachedInputHasItsOwnRate() {
            LlmPrice p = price("2026-01-01", null, "3", "15", "0.30");
            // 800 uncached at 3, 200 cached at 0.30, 100 out at 15
            assertThat(p.cost(1_000, 200, 100)).isEqualByComparingTo("0.00396");
        }

        @Test
        void withoutACachedRateCachedInputCostsAsInput() {
            LlmPrice p = price("2026-01-01", null, "3", "15", null);
            assertThat(p.cost(1_000, 200, 0)).isEqualByComparingTo(p.cost(1_000, 0, 0));
        }

        @Test
        void isExactAndNeverNegative() {
            LlmPrice p = price("2026-01-01", null, "0.075", "0.3", null);
            assertThat(p.cost(1, 0, 1).toPlainString()).isEqualTo("0.000000375");
            assertThat(p.cost(0, 0, 0)).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(p.cost(100, 500, -3)).isEqualByComparingTo(p.cost(100, 100, 0));
        }

        @Test
        void largeCountsDoNotOverflow() {
            LlmPrice p = price("2026-01-01", null, "3", "15", null);
            assertThat(p.cost(3_000_000_000L, 0, 1_000_000_000L)).isEqualByComparingTo("24000");
        }
    }

    @Nested
    class Validation {

        @Test
        void currencyDefaultsToUsdAndIsUpperCased() {
            assertThat(price("2026-01-01", null, "1", "1", null).currency()).isEqualTo("USD");
            assertThat(LlmPrice.of("c", LocalDate.parse("2026-01-01"), null, " eur ", BigDecimal.ONE,
                    BigDecimal.ONE, null).currency()).isEqualTo("EUR");
        }

        @Test
        void refusesWhatCannotBeAPrice() {
            assertThatThrownBy(() -> price("2026-07-01", "2026-07-01", "1", "1", null))
                    .hasMessageContaining("must be after");
            assertThatThrownBy(() -> price("2026-07-01", null, "-1", "1", null))
                    .hasMessageContaining("must not be negative");
            assertThatThrownBy(() -> LlmPrice.of("c", LocalDate.parse("2026-01-01"), null, "dollars",
                    BigDecimal.ONE, BigDecimal.ONE, null)).hasMessageContaining("three-letter");
            assertThatThrownBy(() -> LlmPrice.of("c", LocalDate.parse("2026-01-01"), null, null,
                    null, BigDecimal.ONE, null)).hasMessageContaining("Input price");
            assertThatThrownBy(() -> LlmPrice.of("c", null, null, null,
                    BigDecimal.ONE, BigDecimal.ONE, null)).hasMessageContaining("valid from");
        }
    }
}
