package ai.mindconnect.llm.port.out;

import ai.mindconnect.llm.domain.LlmConfig;
import ai.mindconnect.llm.domain.LlmConfigId;

import java.util.List;
import java.util.Optional;

/**
 * Storage of LLM configurations. An adapter is bound to one namespace when it
 * is built; a config's name is unique within it.
 */
public interface LlmConfigRepository {

    void save(LlmConfig config);

    Optional<LlmConfig> findById(LlmConfigId id);

    Optional<LlmConfig> findByName(String name);

    /** Every config. */
    List<LlmConfig> findAll();

    void deleteById(LlmConfigId id);

    /**
     * Like {@link #findByName(String)}, but follows an alias to the concrete
     * config behind it. Use this whenever the caller reads properties of the model itself —
     * {@code model()}, {@code contextWindowTokens()}, {@code provider()} — because an
     * alias record carries none of them: {@link LlmConfig#alias} leaves them null, so
     * reading them off the alias yields a null model (and with it the char-based
     * fallback token counter) instead of the real one.
     *
     * @throws IllegalStateException if the alias chain is broken, circular, or too deep
     */
    default Optional<LlmConfig> findResolvedByName(String name) {
        return findByName(name)
                .map(config -> config.resolveAlias(
                        target -> findByName(target).orElse(null)));
    }
}
