package ai.mindconnect.llm.service;

import ai.mindconnect.llm.domain.LlmConfig;
import ai.mindconnect.llm.domain.LlmConfigId;
import ai.mindconnect.llm.domain.LlmProvider;
import ai.mindconnect.llm.port.out.LlmConfigRepository;

import java.util.Objects;
import java.util.Optional;

/**
 * Saving and deleting LLM configs with what has to happen alongside, so that
 * every way in — a screen, the REST API, a command line — leaves the same
 * state behind: the prices of a config follow its name, and an LM Studio
 * config never carries another provider's secret to a local server.
 */
public class LlmConfigService {

    /**
     * What an LM Studio config stores as its key. LM Studio takes no key, and
     * the OpenAI-compatible gateway still sends a bearer header — so it sends
     * this, as the bundled configs always have.
     */
    public static final String LM_STUDIO_KEY = "lm-studio";

    private final LlmConfigRepository configs;
    private final LlmPriceService prices;

    /** @param prices the configs' prices; {@code null} on a host that keeps none */
    public LlmConfigService(LlmConfigRepository configs, LlmPriceService prices) {
        this.configs = Objects.requireNonNull(configs, "configs");
        this.prices = prices;
    }

    /** The configs' prices; empty on a host that keeps none. */
    public Optional<LlmPriceService> prices() {
        return Optional.ofNullable(prices);
    }

    /**
     * Saves a new or changed config under its id. A renamed config takes its
     * prices along; an LM Studio config without a key gets the one it had as
     * an LM Studio config, or {@link #LM_STUDIO_KEY}.
     *
     * @throws ai.mindconnect.common.StaleVersionException when the config was saved since the version given
     */
    public LlmConfig save(LlmConfig config) {
        LlmConfig existing = configs.findById(config.id()).orElse(null);
        LlmConfig saved = config.isAlias() ? config
                : config.withApiKey(storedKey(existing, config.provider(), config.apiKey()));
        configs.save(saved);
        if (prices != null && existing != null) {
            prices.configRenamed(existing.name(), saved.name());
        }
        return saved;
    }

    /**
     * Deletes the config and its prices, so a later config of the same name starts unpriced.
     *
     * @return false when there is no such config
     */
    public boolean delete(LlmConfigId id) {
        LlmConfig existing = configs.findById(id).orElse(null);
        if (existing == null) {
            return false;
        }
        configs.deleteById(id);
        if (prices != null) {
            prices.configDeleted(existing.name());
        }
        return true;
    }

    /**
     * The key a config is stored with: the one given, except for an LM Studio
     * config that names none — it keeps the key it had as an LM Studio config
     * (a placeholder, say), and one that was another provider's before gets
     * {@link #LM_STUDIO_KEY} instead of that provider's secret.
     *
     * @param existing the config as stored; {@code null} for a new one
     */
    public static String storedKey(LlmConfig existing, LlmProvider provider, String given) {
        if (provider != LlmProvider.LM_STUDIO || (given != null && !given.isBlank())) {
            return given;
        }
        String kept = existing != null && existing.provider() == LlmProvider.LM_STUDIO ? existing.apiKey() : null;
        return kept == null || kept.isBlank() ? LM_STUDIO_KEY : kept;
    }
}
