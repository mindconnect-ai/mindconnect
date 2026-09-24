package ai.mindconnect.llm.port.out;

import ai.mindconnect.llm.domain.LlmPrice;
import ai.mindconnect.llm.domain.LlmPriceId;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What every {@link LlmPriceRepository} does, whatever it keeps the prices in:
 * a period comes back exactly as saved, its model included, saving again
 * replaces it, a config's periods come back oldest first and without the
 * other configs', and the lookup by model and moment answers from them. Shipped in this module's test jar so
 * the file, in-memory and Postgres adapters all run the same cases.
 */
public abstract class LlmPriceRepositoryContract {

    /** A repository with nothing in it yet. */
    protected abstract LlmPriceRepository repository();

    /** The model the contract's prices are for unless a case says otherwise. */
    protected static final String MODEL = "claude-sonnet-4-6";

    protected static LlmPrice price(String config, String from, String to, String input, String output,
                                    String cached) {
        return price(config, MODEL, from, to, input, output, cached);
    }

    protected static LlmPrice price(String config, String model, String from, String to, String input,
                                    String output, String cached) {
        return LlmPrice.of(config, model, LocalDate.parse(from), to == null ? null : LocalDate.parse(to), "USD",
                new BigDecimal(input), new BigDecimal(output), cached == null ? null : new BigDecimal(cached));
    }

    @Test
    public void aPriceSurvivesTheRoundTripUnchanged() {
        LlmPriceRepository repo = repository();
        LlmPrice price = new LlmPrice(LlmPriceId.random(), "claude", "Claude-Sonnet-4-6", LocalDate.parse("2026-01-01"),
                LocalDate.parse("2026-07-01"), "EUR", new BigDecimal("3.125"), new BigDecimal("15.00"),
                new BigDecimal("0.3"));

        repo.save(price);

        LlmPrice back = repo.findById(price.id()).orElseThrow();
        assertThat(back.id()).isEqualTo(price.id());
        assertThat(back.configName()).isEqualTo("claude");
        assertThat(back.model()).isEqualTo("Claude-Sonnet-4-6");
        assertThat(back.validFrom()).isEqualTo(price.validFrom());
        assertThat(back.validTo()).isEqualTo(price.validTo());
        assertThat(back.currency()).isEqualTo("EUR");
        assertThat(back.inputPerMillion()).isEqualByComparingTo("3.125");
        assertThat(back.outputPerMillion()).isEqualByComparingTo("15");
        assertThat(back.cachedInputPerMillion()).isEqualByComparingTo("0.3");
    }

    @Test
    public void anOpenEndedPriceWithoutACachedRateKeepsBothNulls() {
        LlmPriceRepository repo = repository();
        LlmPrice price = price("gpt", "2026-03-01", null, "1.25", "10", null);

        repo.save(price);

        LlmPrice back = repo.findById(price.id()).orElseThrow();
        assertThat(back.validTo()).isNull();
        assertThat(back.cachedInputPerMillion()).isNull();
    }

    @Test
    public void savingAgainReplacesThePeriod() {
        LlmPriceRepository repo = repository();
        LlmPrice price = price("claude", "2026-01-01", null, "3", "15", null);
        repo.save(price);

        LlmPrice corrected = new LlmPrice(price.id(), "claude", MODEL, price.validFrom(), LocalDate.parse("2026-06-01"),
                "USD", new BigDecimal("2.5"), new BigDecimal("12"), null);
        repo.save(corrected);

        assertThat(repo.findAll()).hasSize(1);
        assertThat(repo.findById(price.id()).orElseThrow().inputPerMillion()).isEqualByComparingTo("2.5");
        assertThat(repo.findById(price.id()).orElseThrow().validTo()).isEqualTo(LocalDate.parse("2026-06-01"));
    }

    @Test
    public void aConfigsPeriodsComeBackOldestFirstAndOnlyItsOwn() {
        LlmPriceRepository repo = repository();
        LlmPrice later = price("claude", "2026-07-01", null, "2", "10", null);
        LlmPrice earlier = price("claude", "2026-01-01", "2026-07-01", "3", "15", null);
        LlmPrice other = price("gpt", "2026-01-01", null, "1", "4", null);
        repo.save(later);
        repo.save(other);
        repo.save(earlier);

        assertThat(repo.findByConfigName("claude")).extracting(LlmPrice::id)
                .containsExactly(earlier.id(), later.id());
        assertThat(repo.findByConfigName("gpt")).extracting(LlmPrice::id).containsExactly(other.id());
        assertThat(repo.findByConfigName("nobody")).isEmpty();
        assertThat(repo.findAll()).hasSize(3);
    }

    @Test
    public void deleteRemovesOneAndForgettingTwiceIsNoError() {
        LlmPriceRepository repo = repository();
        LlmPrice a = price("claude", "2026-01-01", "2026-07-01", "3", "15", null);
        LlmPrice b = price("claude", "2026-07-01", null, "2", "10", null);
        repo.save(a);
        repo.save(b);

        repo.deleteById(a.id());
        repo.deleteById(a.id());

        assertThat(repo.findById(a.id())).isEmpty();
        assertThat(repo.findAll()).extracting(LlmPrice::id).containsExactly(b.id());
    }

    @Test
    public void priceAtAnswersFromTheStoredPeriods() {
        LlmPriceRepository repo = repository();
        LlmPrice first = price("claude", "2026-01-01", "2026-07-01", "3", "15", null);
        LlmPrice second = price("claude", "2026-07-01", null, "2", "10", null);
        repo.save(first);
        repo.save(second);

        assertThat(repo.priceAt("claude", MODEL, Instant.parse("2025-12-31T23:59:59Z"))).isEmpty();
        assertThat(repo.priceAt("claude", MODEL, Instant.parse("2026-06-30T23:59:59Z"))).map(LlmPrice::id)
                .contains(first.id());
        assertThat(repo.priceAt("claude", MODEL, Instant.parse("2026-07-01T00:00:00Z"))).map(LlmPrice::id)
                .contains(second.id());
        assertThat(repo.priceAt("gpt", MODEL, Instant.parse("2026-07-01T00:00:00Z"))).isEmpty();
    }

    @Test
    public void oneConfigCarriesPricesForSeveralModelsAndPriceAtPicksByModelIgnoringCase() {
        LlmPriceRepository repo = repository();
        LlmPrice luna = price("openai-default", "gpt-5.6-luna", "2026-01-01", null, "1.25", "10", null);
        LlmPrice bonsai = price("openai-default", "bonsai-27b", "2026-01-01", null, "0.1", "0.4", null);
        repo.save(luna);
        repo.save(bonsai);
        Instant day = Instant.parse("2026-09-23T15:00:00Z");

        assertThat(repo.findByConfigName("openai-default")).hasSize(2);
        assertThat(repo.priceAt("openai-default", "gpt-5.6-luna", day)).map(LlmPrice::id).contains(luna.id());
        assertThat(repo.priceAt("openai-default", "BONSAI-27B", day)).map(LlmPrice::id).contains(bonsai.id());
        assertThat(repo.priceAt("openai-default", "gpt-5.4-mini", day)).isEmpty();
    }
}
