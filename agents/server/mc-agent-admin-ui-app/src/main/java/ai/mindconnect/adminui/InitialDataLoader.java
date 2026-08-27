package ai.mindconnect.adminui;

import ai.mindconnect.agent.domain.AgentDefinition;
import ai.mindconnect.agent.port.out.AgentDefinitionRepository;
import ai.mindconnect.llm.domain.LlmConfig;
import ai.mindconnect.llm.port.out.LlmConfigRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Loads initial data from {@code classpath:initial-data/} on startup.
 * <p>
 * <ul>
 *   <li>{@code initial-data/llm-configs/*.json} — imported if no config with the same name exists;
 *       if the stored config differs from the classpath version the supplied {@link ConfirmOverwrite}
 *       callback is invoked and the record is overwritten only if it returns {@code true}.</li>
 *   <li>{@code initial-data/agent-definitions/*.json} — same semantics per name+namespace.</li>
 * </ul>
 * New records are always imported. Existing identical records are silently skipped.
 */
@Component
public class InitialDataLoader implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(InitialDataLoader.class);

    /** Called when a stored entity differs from the classpath version. Return true to overwrite. */
    @FunctionalInterface
    public interface ConfirmOverwrite {
        boolean confirm(String entityType, String name, String diff);
    }

    private final LlmConfigRepository llmConfigRepository;
    private final AgentDefinitionRepository agentDefinitionRepository;
    private final ObjectMapper objectMapper;

    public InitialDataLoader(LlmConfigRepository llmConfigRepository,
                             AgentDefinitionRepository agentDefinitionRepository,
                             ObjectMapper objectMapper) {
        this.llmConfigRepository = llmConfigRepository;
        this.agentDefinitionRepository = agentDefinitionRepository;
        this.objectMapper = objectMapper;
    }

    @Override
    public void run(ApplicationArguments args) {
        load();
    }

    /** Load without interactive prompts — existing differing records are skipped with a log warning. */
    public void load() {
        load((type, name, diff) -> {
            log.warn("Initial data '{}' '{}' differs from stored version — skipping (run interactively to overwrite)",
                    type, name);
            return false;
        });
    }

    /** Load with a confirm callback for overwrite decisions. */
    public void load(ConfirmOverwrite confirmOverwrite) {
        loadLlmConfigs(confirmOverwrite);
        loadAgentDefinitions(confirmOverwrite);
    }

    // ── LLM configs ───────────────────────────────────────────────────────────

    private void loadLlmConfigs(ConfirmOverwrite confirm) {
        for (Resource resource : scan("classpath:initial-data/llm-configs/*.json")) {
            try {
                LlmConfig incoming = objectMapper.readValue(resource.getInputStream(), LlmConfig.class);
                llmConfigRepository.findByName(incoming.name()).ifPresentOrElse(existing -> {
                    String diff = diffJson(existing, incoming);
                    if (diff == null) {
                        log.debug("LLM config '{}' is up to date — skipping", incoming.name());
                    } else if (confirm.confirm("LLM config", incoming.name(), diff)) {
                        llmConfigRepository.save(incoming);
                        log.info("Updated LLM config '{}'", incoming.name());
                    } else {
                        log.debug("LLM config '{}' update skipped by user", incoming.name());
                    }
                }, () -> {
                    llmConfigRepository.save(incoming);
                    log.info("Imported LLM config '{}'", incoming.name());
                });
            } catch (Exception e) {
                log.warn("Failed to load LLM config from {}: {}", resource.getFilename(), e.getMessage());
            }
        }
    }

    // ── Agent definitions ─────────────────────────────────────────────────────

    private void loadAgentDefinitions(ConfirmOverwrite confirm) {
        for (Resource resource : scan("classpath:initial-data/agent-definitions/*.json")) {
            try {
                AgentDefinition incoming = objectMapper.readValue(resource.getInputStream(), AgentDefinition.class);
                agentDefinitionRepository.findByName(incoming.namespace(), incoming.name()).ifPresentOrElse(existing -> {
                    String diff = diffJson(existing, incoming);
                    if (diff == null) {
                        log.debug("Agent '{}' is up to date — skipping", incoming.name());
                    } else if (confirm.confirm("agent", incoming.name(), diff)) {
                        agentDefinitionRepository.save(incoming);
                        log.info("Updated agent '{}'", incoming.name());
                    } else {
                        log.debug("Agent '{}' update skipped by user", incoming.name());
                    }
                }, () -> {
                    agentDefinitionRepository.save(incoming);
                    log.info("Imported agent '{}'", incoming.name());
                });
            } catch (Exception e) {
                log.warn("Failed to load agent definition from {}: {}", resource.getFilename(), e.getMessage());
            }
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    /**
     * Returns a human-readable summary of fields that differ between stored and incoming,
     * or {@code null} if they are identical (ignoring {@code updatedAt} / {@code createdAt}).
     */
    private String diffJson(Object stored, Object incoming) {
        try {
            JsonNode storedNode  = objectMapper.valueToTree(stored);
            JsonNode incomingNode = objectMapper.valueToTree(incoming);
            // Strip timestamp fields that are expected to differ
            for (String field : List.of("createdAt", "updatedAt")) {
                ((com.fasterxml.jackson.databind.node.ObjectNode) storedNode).remove(field);
                ((com.fasterxml.jackson.databind.node.ObjectNode) incomingNode).remove(field);
            }
            if (storedNode.equals(incomingNode)) return null;

            StringBuilder sb = new StringBuilder();
            incomingNode.fields().forEachRemaining(entry -> {
                String key = entry.getKey();
                JsonNode newVal = entry.getValue();
                JsonNode oldVal = storedNode.get(key);
                if (oldVal != null && !oldVal.equals(newVal)) {
                    sb.append("  ").append(key).append(": ")
                      .append(oldVal).append(" → ").append(newVal).append("\n");
                }
            });
            return sb.isEmpty() ? "(structural difference)" : sb.toString();
        } catch (Exception e) {
            return "(could not diff: " + e.getMessage() + ")";
        }
    }

    private List<Resource> scan(String pattern) {
        try {
            PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
            return List.of(resolver.getResources(pattern));
        } catch (Exception e) {
            log.debug("No resources found for pattern {}: {}", pattern, e.getMessage());
            return List.of();
        }
    }
}
