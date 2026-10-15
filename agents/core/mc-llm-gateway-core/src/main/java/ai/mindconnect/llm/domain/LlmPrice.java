package ai.mindconnect.llm.domain;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/**
 * What one model costs through one LLM config during one period, as a rate per
 * million input, output and cached input tokens. A price is keyed by the (config,
 * model) pair because a config's model changes over time and a price must never
 * price another model's calls; the periods of one pair are half-open UTC days that
 * never overlap. A price stored before prices named their model prices any model of
 * its config, behind one that names it. Separate from {@link LlmConfig} on purpose:
 * a corrected price must apply to calls long made.
 *
 * @param id                    unique per period
 * @param configName            the concrete LLM config it prices
 * @param model                 the model it prices, as the call reports it; null only in a price stored
 *                              before prices named their model, which then prices any model of the config
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
        String model,
        @JsonFormat(shape = JsonFormat.Shape.STRING) LocalDate validFrom,
        @JsonFormat(shape = JsonFormat.Shape.STRING) LocalDate validTo,
        String currency,
        BigDecimal inputPerMillion,
        BigDecimal outputPerMillion,
        BigDecimal cachedInputPerMillion
) {

    public static final String DEFAULT_CURRENCY = "USD";
    private static final Pattern CURRENCY = Pattern.compile("[A-Z]{3}");
    private static final Logger log = LoggerFactory.getLogger(LlmPrice.class);
    /** A price without a model is logged once per process — they are leftovers, not news on every read. */
    private static final AtomicBoolean ANY_MODEL_LOGGED = new AtomicBoolean();

    public LlmPrice {
        Objects.requireNonNull(id, "id");
        if (configName == null || configName.isBlank()) {
            throw new IllegalArgumentException("A price needs the LLM config it prices");
        }
        model = model == null || model.isBlank() ? null : model.strip();
        if (model == null && ANY_MODEL_LOGGED.compareAndSet(false, true)) {
            log.warn("LLM price {} of config '{}' names no model: it prices every model of that config until "
                    + "it is saved with one (logged once — there may be more)", id, configName);
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

    /** A new period with a fresh id; the model is required. */
    public static LlmPrice of(String configName, String model, LocalDate validFrom, LocalDate validTo,
                              String currency, BigDecimal inputPerMillion, BigDecimal outputPerMillion,
                              BigDecimal cachedInputPerMillion) {
        return new LlmPrice(LlmPriceId.random(), configName, requireModel(model), validFrom, validTo, currency,
                inputPerMillion, outputPerMillion, cachedInputPerMillion);
    }

    /** {@code model}, stripped; refuses a missing one — what a new or edited price must name. */
    public static String requireModel(String model) {
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("A price needs the model it prices, as the provider names it "
                    + "(gpt-5.4-mini, claude-sonnet-4-6)");
        }
        return model.strip();
    }

    private static BigDecimal requireRate(BigDecimal rate, String what) {
        if (rate == null) throw new IllegalArgumentException(what + " price per million tokens is required");
        if (rate.signum() < 0) throw new IllegalArgumentException(what + " price must not be negative");
        return rate;
    }

    /** The same period for another config — what a rename of the config does to its prices. */
    public LlmPrice withConfigName(String name) {
        return new LlmPrice(id, name, model, validFrom, validTo, currency, inputPerMillion, outputPerMillion,
                cachedInputPerMillion);
    }

    /** True for a price stored before prices named their model: it prices any model of its config. */
    public boolean anyModel() {
        return model == null;
    }

    /** The name a model is shown and grouped under; a price without one prices any model. */
    public String modelLabel() {
        return anyModel() ? "any model" : model;
    }

    /** The period as people read it: {@code 2026-01-01 – 2026-07-01} or {@code 2026-07-01 – open}. */
    public String period() {
        return validFrom + " – " + (validTo == null ? "open" : validTo.toString());
    }

    /**
     * Whether this price is for {@code model}: the same name, ignoring case —
     * or any model at all for a price that names none ({@link #anyModel}).
     */
    public boolean isFor(String model) {
        if (this.model == null) return true;
        return model != null && this.model.equalsIgnoreCase(model.strip());
    }

    /** Whether the two price the same model of their config — the set whose periods must not overlap. */
    public boolean sameModelAs(LlmPrice other) {
        return this.model == null ? other.model == null
                : other.model != null && this.model.equalsIgnoreCase(other.model);
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
