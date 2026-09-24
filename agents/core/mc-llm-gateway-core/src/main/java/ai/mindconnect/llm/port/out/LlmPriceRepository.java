package ai.mindconnect.llm.port.out;

import ai.mindconnect.llm.domain.LlmPrice;
import ai.mindconnect.llm.domain.LlmPriceId;
import ai.mindconnect.llm.domain.LlmPrices;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Storage of LLM prices — one entry per price period of a config. An adapter
 * is bound to one namespace when it is built, like {@link LlmConfigRepository}.
 *
 * <p>The store keeps what it is given; the rules (no overlapping periods, no
 * price for an alias) are the service's, which checks them before it saves.
 */
public interface LlmPriceRepository {

    void save(LlmPrice price);

    Optional<LlmPrice> findById(LlmPriceId id);

    /** The periods of one config, oldest first. */
    List<LlmPrice> findByConfigName(String configName);

    /** Every period of every config. */
    List<LlmPrice> findAll();

    void deleteById(LlmPriceId id);

    /**
     * The price of {@code configName} at {@code at}, the moment read as a UTC
     * day; empty when none is valid then. The name is taken as it is: an alias
     * has no prices, so resolve it to its concrete config first.
     */
    default Optional<LlmPrice> priceAt(String configName, Instant at) {
        return LlmPrices.priceAt(findByConfigName(configName), configName, at);
    }
}
