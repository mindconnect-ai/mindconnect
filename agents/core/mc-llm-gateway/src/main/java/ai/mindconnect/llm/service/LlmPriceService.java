package ai.mindconnect.llm.service;

import ai.mindconnect.llm.domain.LlmConfig;
import ai.mindconnect.llm.domain.LlmPrice;
import ai.mindconnect.llm.domain.LlmPriceId;
import ai.mindconnect.llm.domain.LlmPrices;
import ai.mindconnect.llm.port.out.LlmConfigRepository;
import ai.mindconnect.llm.port.out.LlmPriceRepository;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Keeps the price periods of the LLM configs consistent: a price is for a
 * concrete config that exists and names the model it prices, and the periods
 * of one config and model never overlap — those of different models of one
 * config are independent. The model is not checked against the config's: it
 * may be one the config served before, or one it is about to serve.
 * Reading goes straight to the {@link LlmPriceRepository}; this is the way
 * to write.
 *
 * <p>What happens to prices when their config changes: a rename carries them
 * over to the new name ({@link #configRenamed}), a delete removes them
 * ({@link #configDeleted}). A config removed some other way — the REST API,
 * a file deleted by hand — leaves its prices behind; they harm nothing,
 * because nothing asks for that name any more, and they reappear on a new
 * config of the same name, where the admin sees them in its pricing section.
 */
public class LlmPriceService {

    private final LlmPriceRepository prices;
    private final LlmConfigRepository configs;

    public LlmPriceService(LlmPriceRepository prices, LlmConfigRepository configs) {
        this.prices = Objects.requireNonNull(prices, "prices");
        this.configs = Objects.requireNonNull(configs, "configs");
    }

    /** The periods of one config, every model's, oldest first. */
    public List<LlmPrice> pricesOf(String configName) {
        return prices.findByConfigName(configName);
    }

    public Optional<LlmPrice> find(LlmPriceId id) {
        return prices.findById(id);
    }

    /**
     * Saves a new or changed period after checking it.
     *
     * @throws IllegalArgumentException when the config is unknown or an alias, the model
     *                                  is missing, or the period overlaps another of the
     *                                  same config and model — the message says which,
     *                                  for the form to show
     */
    public LlmPrice save(LlmPrice price) {
        LlmConfig config = configs.findByName(price.configName())
                .orElseThrow(() -> new IllegalArgumentException(
                        "There is no LLM config named '" + price.configName() + "' to price"));
        LlmPrice.requireModel(price.model());
        if (config.isAlias()) {
            throw new IllegalArgumentException("'" + config.name() + "' is an alias: it uses the prices of '"
                    + config.delegatesTo() + "', its target. Set the price there.");
        }
        List<LlmPrice> clashes = LlmPrices.overlapping(prices.findByConfigName(price.configName()), price);
        if (!clashes.isEmpty()) {
            throw new IllegalArgumentException("The period " + LlmPrices.period(price)
                    + " overlaps " + clashes.stream().map(LlmPrices::period)
                    .collect(Collectors.joining(", ")) + " of " + price.model() + " on '" + price.configName()
                    + "'. Periods of one model must not overlap — end the earlier one on the day the next starts.");
        }
        prices.save(price);
        return price;
    }

    public void delete(LlmPriceId id) {
        prices.deleteById(id);
    }

    /** A config was renamed: its prices follow it to the new name. */
    public void configRenamed(String oldName, String newName) {
        if (oldName == null || newName == null || oldName.equals(newName)) return;
        for (LlmPrice price : prices.findByConfigName(oldName)) {
            prices.save(price.withConfigName(newName));
        }
    }

    /** A config was deleted: its prices go with it. */
    public void configDeleted(String name) {
        if (name == null) return;
        for (LlmPrice price : prices.findByConfigName(name)) {
            prices.deleteById(price.id());
        }
    }
}
