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

    static final String SONNET = "claude-sonnet-4-6";

    static LlmPrice price(String from, String to, String input, String output, String cached) {
        return LlmPrice.of("claude", SONNET, LocalDate.parse(from), to == null ? null : LocalDate.parse(to), null,
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
            assertThat(LlmPrices.priceAt(List.of(h1, open), "claude", SONNET, Instant.parse("2026-06-30T23:30:00Z")))
                    .contains(h1);
        }

        @Test
        void priceAtPicksThePeriodOfTheDayAndOnlyOfThatConfig() {
            LlmPrice gpt = LlmPrice.of("gpt", "gpt-5.4-mini", LocalDate.parse("2020-01-01"), null, "USD",
                    BigDecimal.ONE, BigDecimal.TEN, null);
            List<LlmPrice> all = List.of(h1, open, gpt);

            assertThat(LlmPrices.priceAt(all, "claude", SONNET, Instant.parse("2026-03-01T10:00:00Z"))).contains(h1);
            assertThat(LlmPrices.priceAt(all, "claude", SONNET, Instant.parse("2026-07-01T00:00:00Z"))).contains(open);
            assertThat(LlmPrices.priceAt(all, "claude", SONNET, Instant.parse("2025-03-01T10:00:00Z"))).isEmpty();
            assertThat(LlmPrices.priceAt(all, "nobody", SONNET, Instant.parse("2026-03-01T10:00:00Z"))).isEmpty();
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
            LlmPrice edited = new LlmPrice(stored.id(), "claude", SONNET, LocalDate.parse("2026-02-01"), null, "USD",
                    BigDecimal.ONE, BigDecimal.ONE, null);
            LlmPrice otherConfig = LlmPrice.of("gpt", "gpt-5.4-mini", LocalDate.parse("2026-01-01"), null, "USD",
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
            assertThat(LlmPrice.of("c", "m", LocalDate.parse("2026-01-01"), null, " eur ", BigDecimal.ONE,
                    BigDecimal.ONE, null).currency()).isEqualTo("EUR");
        }

        @Test
        void refusesWhatCannotBeAPrice() {
            assertThatThrownBy(() -> price("2026-07-01", "2026-07-01", "1", "1", null))
                    .hasMessageContaining("must be after");
            assertThatThrownBy(() -> price("2026-07-01", null, "-1", "1", null))
                    .hasMessageContaining("must not be negative");
            assertThatThrownBy(() -> LlmPrice.of("c", "m", LocalDate.parse("2026-01-01"), null, "dollars",
                    BigDecimal.ONE, BigDecimal.ONE, null)).hasMessageContaining("three-letter");
            assertThatThrownBy(() -> LlmPrice.of("c", "m", LocalDate.parse("2026-01-01"), null, null,
                    null, BigDecimal.ONE, null)).hasMessageContaining("Input price");
            assertThatThrownBy(() -> LlmPrice.of("c", "m", null, null, null,
                    BigDecimal.ONE, BigDecimal.ONE, null)).hasMessageContaining("valid from");
        }
    }

    @Nested
    class Model {

        final LlmPrice luna = LlmPrice.of("openai-default", "gpt-5.6-luna", LocalDate.parse("2026-01-01"), null,
                "USD", new BigDecimal("1.25"), BigDecimal.TEN, null);
        final LlmPrice bonsai = LlmPrice.of("openai-default", "bonsai-27b", LocalDate.parse("2026-01-01"), null,
                "USD", new BigDecimal("0.10"), BigDecimal.ONE, null);
        final Instant day = Instant.parse("2026-09-23T12:00:00Z");

        @Test
        void aPriceNamesItsModelStrippedAndRefusesToBeWithoutOne() {
            assertThat(LlmPrice.of("c", "  gpt-5.4-mini ", LocalDate.parse("2026-01-01"), null, null,
                    BigDecimal.ONE, BigDecimal.ONE, null).model()).isEqualTo("gpt-5.4-mini");
            assertThatThrownBy(() -> LlmPrice.of("c", " ", LocalDate.parse("2026-01-01"), null, null,
                    BigDecimal.ONE, BigDecimal.ONE, null)).hasMessageContaining("needs the model");
        }

        @Test
        void priceAtAnswersOnlyForTheModelOfTheCall() {
            List<LlmPrice> all = List.of(luna, bonsai);

            assertThat(LlmPrices.priceAt(all, "openai-default", "gpt-5.6-luna", day)).contains(luna);
            assertThat(LlmPrices.priceAt(all, "openai-default", "bonsai-27b", day)).contains(bonsai);
            assertThat(LlmPrices.priceAt(all, "openai-default", "gpt-5.4-mini", day)).isEmpty();
            assertThat(LlmPrices.priceAt(all, "openai-default", null, day)).isEmpty();
            assertThat(LlmPrices.priceAt(all, "other", "bonsai-27b", day)).isEmpty();
        }

        @Test
        void theModelMatchesIgnoringCase() {
            assertThat(LlmPrices.priceAt(List.of(luna), "openai-default", "GPT-5.6-Luna", day)).contains(luna);
            assertThat(luna.isFor(" gpt-5.6-LUNA ")).isTrue();
        }

        @Test
        void periodsOverlapOnlyWithinOneModel() {
            LlmPrice lunaLater = LlmPrice.of("openai-default", "GPT-5.6-LUNA", LocalDate.parse("2026-06-01"), null,
                    "USD", BigDecimal.ONE, BigDecimal.ONE, null);

            assertThat(LlmPrices.overlapping(List.of(luna), bonsai)).isEmpty();
            assertThat(LlmPrices.overlapping(List.of(luna, bonsai), lunaLater)).containsExactly(luna);
        }

        @Test
        void aPriceStoredWithoutAModelPricesAnyModelBehindOneThatNamesIt() {
            LlmPrice legacy = new LlmPrice(LlmPriceId.random(), "openai-default", null,
                    LocalDate.parse("2026-01-01"), null, "USD", BigDecimal.ONE, BigDecimal.ONE, null);

            assertThat(legacy.anyModel()).isTrue();
            assertThat(LlmPrices.priceAt(List.of(legacy, luna), "openai-default", "gpt-5.6-luna", day))
                    .contains(luna);
            assertThat(LlmPrices.priceAt(List.of(legacy, luna), "openai-default", "bonsai-27b", day))
                    .contains(legacy);
            assertThat(LlmPrices.overlapping(List.of(legacy), luna)).isEmpty();
        }

        @Test
        void byModelGroupsThePeriodsCaseInsensitivelyWithoutAModelLast() {
            LlmPrice lunaUpper = LlmPrice.of("openai-default", "GPT-5.6-LUNA", LocalDate.parse("2025-01-01"),
                    LocalDate.parse("2026-01-01"), "USD", BigDecimal.ONE, BigDecimal.ONE, null);
            LlmPrice legacy = new LlmPrice(LlmPriceId.random(), "openai-default", null,
                    LocalDate.parse("2024-01-01"), null, "USD", BigDecimal.ONE, BigDecimal.ONE, null);

            var groups = LlmPrices.byModel(List.of(luna, legacy, bonsai, lunaUpper));

            assertThat(groups.keySet()).containsExactly("bonsai-27b", "gpt-5.6-luna", null);
            assertThat(groups.get("gpt-5.6-luna")).containsExactly(lunaUpper, luna);
            assertThat(LlmPrices.modelLabel(legacy)).isEqualTo("any model");
        }
    }
}
