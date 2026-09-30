package ai.mindconnect.llm.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * The pure rules over a config's price periods: which one applies to a
 * model at a moment, and whether a new or changed period fits between the
 * others of its model.
 * No storage — the repository and the service call these.
 */
public final class LlmPrices {

    /** Oldest period first. */
    public static final Comparator<LlmPrice> CHRONOLOGICAL =
            Comparator.comparing(LlmPrice::validFrom).thenComparing(p -> p.id().value());

    /** The name a model is shown and grouped under; a price without one prices any model. */
    public static String modelLabel(LlmPrice price) {
        return price.anyModel() ? "any model" : price.model();
    }

    private LlmPrices() {
    }

    /**
     * The price of {@code model} through {@code configName} valid at
     * {@code at} (a UTC day), among {@code prices}; empty when no period of
     * that pair covers the day. The model matches ignoring case. Periods of
     * other configs or other models in the collection are ignored — a price
     * never prices another model's calls. A price stored without a model
     * ({@link LlmPrice#anyModel}) answers only when none names the model.
     */
    public static Optional<LlmPrice> priceAt(Collection<LlmPrice> prices, String configName, String model,
                                             Instant at) {
        if (configName == null || at == null) return Optional.empty();
        List<LlmPrice> valid = prices.stream()
                .filter(p -> p.configName().equals(configName))
                .filter(p -> p.isFor(model))
                .filter(p -> p.isValidAt(at))
                .toList();
        return valid.stream().filter(p -> !p.anyModel()).max(CHRONOLOGICAL)
                .or(() -> valid.stream().max(CHRONOLOGICAL));
    }

    /**
     * The periods of the same config and model that {@code candidate} would
     * overlap — itself (same id) excluded, so an edit is checked against the
     * others only. Another model's periods of the same config never clash.
     */
    public static List<LlmPrice> overlapping(Collection<LlmPrice> existing, LlmPrice candidate) {
        return existing.stream()
                .filter(p -> p.configName().equals(candidate.configName()))
                .filter(candidate::sameModelAs)
                .filter(p -> !p.id().equals(candidate.id()))
                .filter(candidate::overlaps)
                .sorted(CHRONOLOGICAL)
                .toList();
    }

    /**
     * A config's periods grouped by model, the models alphabetical (ignoring
     * case: spellings that differ only in case are one group, keyed by the
     * newest period's), each group oldest first; periods stored without a
     * model come last, under a null key.
     */
    public static Map<String, List<LlmPrice>> byModel(Collection<LlmPrice> prices) {
        Map<String, List<LlmPrice>> groups = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        List<LlmPrice> any = new ArrayList<>();
        for (LlmPrice p : prices.stream().sorted(CHRONOLOGICAL).toList()) {
            if (p.anyModel()) any.add(p);
            else groups.computeIfAbsent(p.model(), m -> new ArrayList<>()).add(p);
        }
        Map<String, List<LlmPrice>> out = new LinkedHashMap<>();
        groups.values().forEach(periods -> out.put(periods.get(periods.size() - 1).model(), periods));
        if (!any.isEmpty()) out.put(null, any);
        return out;
    }

    /** A period as people read it: {@code 2026-01-01 – 2026-07-01} or {@code 2026-07-01 – open}. */
    public static String period(LlmPrice price) {
        return price.validFrom() + " – " + (price.validTo() == null ? "open" : price.validTo().toString());
    }
}
