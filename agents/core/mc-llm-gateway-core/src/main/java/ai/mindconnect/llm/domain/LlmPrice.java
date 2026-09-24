package ai.mindconnect.llm.domain;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * What one LLM config costs during one period: a rate per million input,
 * output and (optionally) cached input tokens, in one currency.
 *
 * <p>A config has as many of these as its rates changed. The periods are
 * days in UTC, half-open: {@code validFrom} is the first day the price
 * applies, {@code validTo} the first day it no longer does, and a missing
 * {@code validTo} means "until further notice". Periods of one config must
 * not overlap — {@link #overlaps} says whether two do, the service that
 * saves them refuses it.
 *
 * <p>A price belongs to a <em>concrete</em> config, by name. An alias has no
 * price of its own: its calls are served by the config it points at and
 * cost what that one costs.
 *
 * <p>Separate from {@link LlmConfig} on purpose: a config says how to call a
 * model, a price what a call cost at the time — and a corrected price must
 * be able to apply to calls long made.
 *
 * @param id                    unique per period
 * @param configName            the concrete LLM config it prices
 * @param validFrom             the first UTC day it applies (inclusive)
 * @param validTo               the first UTC day it no longer applies (exclusive), or null for open-ended
 * @param currency              ISO 4217 code, {@code USD} when not given
 * @param inputPerMillion       per 1,000,000 input tokens
 * @param outputPerMillion      per 1,000,000 output tokens
 * @param cachedInputPerMillion per 1,000,000 input tokens read from the provider's cache; null prices them as input
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record LlmPrice(
        LlmPriceId id,
        String configName,
        @JsonFormat(shape = JsonFormat.Shape.STRING) LocalDate validFrom,
        @JsonFormat(shape = JsonFormat.Shape.STRING) LocalDate validTo,
        String currency,
        BigDecimal inputPerMillion,
        BigDecimal outputPerMillion,
        BigDecimal cachedInputPerMillion
) {

    public static final String DEFAULT_CURRENCY = "USD";
    private static final Pattern CURRENCY = Pattern.compile("[A-Z]{3}");

    public LlmPrice {
        Objects.requireNonNull(id, "id");
        if (configName == null || configName.isBlank()) {
            throw new IllegalArgumentException("A price needs the LLM config it prices");
        }
        if (validFrom == null) {
            throw new IllegalArgumentException("A price needs the day it is valid from");
        }
        if (validTo != null && !validTo.isAfter(validFrom)) {
            throw new IllegalArgumentException("'Valid to' (" + validTo + ") must be after 'valid from' ("
                    + validFrom + ") — it is the first day the price no longer applies");
        }
        currency = currency == null || currency.isBlank() ? DEFAULT_CURRENCY : currency.strip().toUpperCase(Locale.ROOT);
        if (!CURRENCY.matcher(currency).matches()) {
            throw new IllegalArgumentException("Currency must be a three-letter ISO code such as USD or EUR, not '"
                    + currency + "'");
        }
        inputPerMillion = requireRate(inputPerMillion, "Input");
        outputPerMillion = requireRate(outputPerMillion, "Output");
        if (cachedInputPerMillion != null) cachedInputPerMillion = requireRate(cachedInputPerMillion, "Cached input");
    }

    /** A new period with a fresh id. */
    public static LlmPrice of(String configName, LocalDate validFrom, LocalDate validTo, String currency,
                              BigDecimal inputPerMillion, BigDecimal outputPerMillion,
                              BigDecimal cachedInputPerMillion) {
        return new LlmPrice(LlmPriceId.random(), configName, validFrom, validTo, currency,
                inputPerMillion, outputPerMillion, cachedInputPerMillion);
    }

    private static BigDecimal requireRate(BigDecimal rate, String what) {
        if (rate == null) throw new IllegalArgumentException(what + " price per million tokens is required");
        if (rate.signum() < 0) throw new IllegalArgumentException(what + " price must not be negative");
        return rate;
    }

    /** The same period for another config — what a rename of the config does to its prices. */
    public LlmPrice withConfigName(String name) {
        return new LlmPrice(id, name, validFrom, validTo, currency, inputPerMillion, outputPerMillion,
                cachedInputPerMillion);
    }

    /** Whether this price applies on {@code day}: from inclusive, to exclusive, open when there is no end. */
    public boolean isValidOn(LocalDate day) {
        return !day.isBefore(validFrom) && (validTo == null || day.isBefore(validTo));
    }

    /** Whether this price applies at {@code at}, taken as a UTC day. */
    public boolean isValidAt(Instant at) {
        return isValidOn(at.atOffset(ZoneOffset.UTC).toLocalDate());
    }

    /** Whether the two periods share a day. Half-open intervals: one ending where the other starts do not. */
    public boolean overlaps(LlmPrice other) {
        boolean thisEndsBefore = validTo != null && !validTo.isAfter(other.validFrom);
        boolean otherEndsBefore = other.validTo != null && !other.validTo.isAfter(validFrom);
        return !thisEndsBefore && !otherEndsBefore;
    }

    /** The rate cached input is charged at: its own when set, else the input rate. */
    public BigDecimal effectiveCachedInputPerMillion() {
        return cachedInputPerMillion != null ? cachedInputPerMillion : inputPerMillion;
    }

    /**
     * What one call cost at these rates, unrounded.
     *
     * @param inputTokens       every input token of the call, cached ones included
     * @param cachedInputTokens the part of {@code inputTokens} the provider read from its cache
     * @param outputTokens      the output tokens
     */
    public BigDecimal cost(long inputTokens, long cachedInputTokens, long outputTokens) {
        long cached = Math.max(0, Math.min(cachedInputTokens, inputTokens));
        long uncached = Math.max(0, inputTokens) - cached;
        BigDecimal sum = inputPerMillion.multiply(BigDecimal.valueOf(uncached))
                .add(effectiveCachedInputPerMillion().multiply(BigDecimal.valueOf(cached)))
                .add(outputPerMillion.multiply(BigDecimal.valueOf(Math.max(0, outputTokens))));
        return sum.movePointLeft(6);
    }
}
