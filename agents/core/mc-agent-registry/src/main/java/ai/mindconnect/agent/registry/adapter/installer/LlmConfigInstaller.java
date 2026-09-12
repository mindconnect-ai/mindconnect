package ai.mindconnect.agent.registry.adapter.installer;

import ai.mindconnect.agent.registry.domain.ImportMode;
import ai.mindconnect.agent.registry.domain.ImportStatus;
import ai.mindconnect.agent.registry.domain.ImportedItem;
import ai.mindconnect.agent.registry.domain.RegistryEntry;
import ai.mindconnect.agent.registry.domain.RegistryItemType;
import ai.mindconnect.agent.registry.port.out.RegistryInstaller;
import ai.mindconnect.llm.domain.LlmConfig;
import ai.mindconnect.llm.domain.LlmConfigId;
import ai.mindconnect.llm.port.out.LlmConfigRepository;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

/**
 * Installs an {@code llm-config} entry.
 *
 * <p>Two things are taken away from the imported file, both for the same
 * reason — a registry is somebody else's repository:
 *
 * <ul>
 *   <li><b>The id.</b> A new config gets a fresh one; re-importing over an
 *       existing config keeps the local id and version, so the agents pointing
 *       at it keep working and the optimistic lock still holds.</li>
 *   <li><b>A literal API key.</b> A registry config is meant to carry
 *       {@code ${OPENAI_API_KEY}}, which resolves against this installation's
 *       environment at call time. A key that is not a placeholder is somebody
 *       else's credential — it is dropped, and the report says so, rather than
 *       this installation quietly billing a stranger.</li>
 * </ul>
 */
public class LlmConfigInstaller implements RegistryInstaller {

    private static final Logger log = LoggerFactory.getLogger(LlmConfigInstaller.class);

    private final LlmConfigRepository repository;
    private final ObjectMapper objectMapper;

    public LlmConfigInstaller(LlmConfigRepository repository) {
        this.repository = repository;
        this.objectMapper = new ObjectMapper()
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    @Override
    public RegistryItemType type() {
        return RegistryItemType.LLM_CONFIG;
    }

    @Override
    public boolean exists(String name) {
        return name != null && repository.findByName(name).isPresent();
    }

    @Override
    public ImportedItem install(RegistryEntry entry, String content, ImportMode mode) throws Exception {
        LlmConfig incoming = read(content);
        String name = incoming.name() != null && !incoming.name().isBlank()
                ? incoming.name() : entry.name();
        Optional<LlmConfig> existing = repository.findByName(name);

        if (existing.isPresent() && mode == ImportMode.SKIP_EXISTING) {
            return ImportedItem.skipped(entry, name, "an LLM config of this name is already here");
        }

        boolean keyDropped = isLiteralSecret(incoming.apiKey());
        LlmConfig toSave = rewrite(incoming, name,
                existing.map(LlmConfig::id).orElseGet(LlmConfigId::random),
                existing.map(LlmConfig::version).orElse(null),
                keyDropped ? null : incoming.apiKey());
        repository.save(toSave);
        log.info("Imported LLM config '{}' from registry entry '{}'", name, entry.id());

        String detail = keyDropped
                ? "the file's API key was dropped — set one here, or use a ${ENV_VAR} placeholder"
                : null;
        return new ImportedItem(entry.id(), type(), name,
                existing.isPresent() ? ImportStatus.UPDATED : ImportStatus.IMPORTED, detail);
    }

    /**
     * The file as an {@link LlmConfig}.
     *
     * <p>A registry file carries no id — an id addresses an entity in one
     * installation's store, and the config's own reader insists on a valid one.
     * So a placeholder goes in before parsing; {@link #install} then puts the
     * local id in its place.
     */
    private LlmConfig read(String content) throws Exception {
        JsonNode tree = objectMapper.readTree(content);
        if (!(tree instanceof ObjectNode object)) {
            throw new IllegalArgumentException("An LLM config file has to be a JSON object");
        }
        JsonNode id = object.get("id");
        if (id == null || id.isNull() || id.asText().isBlank()) {
            object.put("id", LlmConfigId.random().value());
        }
        return objectMapper.treeToValue(object, LlmConfig.class);
    }

    /** The incoming config under the local id, name, version and key. */
    private static LlmConfig rewrite(LlmConfig incoming, String name, LlmConfigId id,
                                     Long version, String apiKey) {
        return new LlmConfig(id, name, incoming.provider(), incoming.model(), incoming.baseUrl(),
                apiKey, incoming.defaultTemperature(), incoming.maxOutputTokens(),
                incoming.additionalParams(), incoming.contextWindowTokens(), incoming.isAlias(),
                incoming.delegatesTo(), incoming.retry(), incoming.rateLimit(), incoming.type(),
                incoming.capabilities(), version);
    }

    /**
     * Whether this key is a value rather than a reference. {@code ${OPENAI_API_KEY}}
     * and {@code ${KEY:fallback}} are references this installation resolves for
     * itself; anything else that is not blank came with the file.
     */
    static boolean isLiteralSecret(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) return false;
        String value = apiKey.strip();
        return !(value.startsWith("${") && value.endsWith("}"));
    }
}
