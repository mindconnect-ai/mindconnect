package ai.mindconnect.llm.domain;

import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * The pure rules over a config's price periods: which one applies at a
 * moment, and whether a new or changed period fits between the others.
 * No storage — the repository and the service call these.
 */
public final class LlmPrices {

    /** Oldest period first. */
    public static final Comparator<LlmPrice> CHRONOLOGICAL =
            Comparator.comparing(LlmPrice::validFrom).thenComparing(p -> p.id().value());

    private LlmPrices() {
    }

    /**
     * The price of {@code configName} valid at {@code at} (a UTC day), among
     * {@code prices}; empty when no period covers that day. Periods of other
     * configs in the collection are ignored.
     */
    public static Optional<LlmPrice> priceAt(Collection<LlmPrice> prices, String configName, Instant at) {
        if (configName == null || at == null) return Optional.empty();
        return prices.stream()
                .filter(p -> p.configName().equals(configName))
                .filter(p -> p.isValidAt(at))
                .max(CHRONOLOGICAL);
    }

    /**
     * The periods of the same config that {@code candidate} would overlap —
     * itself (same id) excluded, so an edit is checked against the others only.
     */
    public static List<LlmPrice> overlapping(Collection<LlmPrice> existing, LlmPrice candidate) {
        return existing.stream()
                .filter(p -> p.configName().equals(candidate.configName()))
                .filter(p -> !p.id().equals(candidate.id()))
                .filter(candidate::overlaps)
                .sorted(CHRONOLOGICAL)
                .toList();
    }

    /** A period as people read it: {@code 2026-01-01 – 2026-07-01} or {@code 2026-07-01 – open}. */
    public static String period(LlmPrice price) {
        return price.validFrom() + " – " + (price.validTo() == null ? "open" : price.validTo().toString());
    }
}
