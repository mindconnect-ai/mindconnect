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
     * config behind it — what every caller that reads the model, the provider
     * or the context window needs, because an alias carries none of them.
     *
     * @throws IllegalStateException if the alias chain is broken, circular, or too deep
     */
    default Optional<LlmConfig> findResolvedByName(String name) {
        return findByName(name)
                .map(config -> config.resolveAlias(
                        target -> findByName(target).orElse(null)));
    }
}
