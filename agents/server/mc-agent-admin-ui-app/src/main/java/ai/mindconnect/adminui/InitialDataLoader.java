package ai.mindconnect.adminui;

import ai.mindconnect.agent.runtime.domain.AgentDefinition;
import ai.mindconnect.agent.runtime.port.out.AgentDefinitionRepository;
import ai.mindconnect.agent.runtime.skill.Skill;
import ai.mindconnect.agent.runtime.skill.SkillRepository;
import ai.mindconnect.agent.runtime.skill.SkillSource;
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
 *   <li>{@code initial-data/agent-definitions/*.json} — same semantics per name.</li>
 *   <li>{@code initial-data/skills/*.md} — one {@code SKILL.md} per skill, imported
 *       when no skill of that name is stored. A stored one is never touched: a
 *       skill is text someone edits, and the shipped version has nothing to say
 *       about what they made of it.</li>
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
    private final SkillRepository skillRepository;
    private final ObjectMapper objectMapper;

    public InitialDataLoader(LlmConfigRepository llmConfigRepository,
                             AgentDefinitionRepository agentDefinitionRepository,
                             SkillRepository skillRepository,
                             ObjectMapper objectMapper) {
        this.llmConfigRepository = llmConfigRepository;
        this.agentDefinitionRepository = agentDefinitionRepository;
        this.skillRepository = skillRepository;
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
        loadSkills();
    }

    // ── LLM configs ───────────────────────────────────────────────────────────

    private void loadLlmConfigs(ConfirmOverwrite confirm) {
        for (Resource resource : scan("classpath:initial-data/llm-configs/*.json")) {
            try {
                LlmConfig incoming = read(resource, LlmConfig.class);
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
                AgentDefinition incoming = read(resource, AgentDefinition.class);
                agentDefinitionRepository.findByName(incoming.name()).ifPresentOrElse(existing -> {
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

    // ── Skills ────────────────────────────────────────────────────────────────

    /**
     * Imports the shipped {@code SKILL.md} files, and only those the store
     * does not already know by name. No overwrite prompt: unlike a config,
     * a skill is prose someone has since rewritten for their own house, and
     * the shipped wording has no claim on it.
     */
    private void loadSkills() {
        for (Resource resource : scan("classpath:initial-data/skills/*.md")) {
            String fileName = resource.getFilename() == null ? "skill" : resource.getFilename();
            String fallback = fileName.endsWith(".md")
                    ? fileName.substring(0, fileName.length() - 3) : fileName;
            try {
                String content = new String(resource.getInputStream().readAllBytes(),
                        java.nio.charset.StandardCharsets.UTF_8);
                Skill incoming = Skill.fromMarkdown(fallback, content, SkillSource.MANAGED, null);
                if (incoming == null) {
                    log.warn("Initial skill {} has no instructions, or a name that is not lower-case "
                            + "letters, digits and dashes — skipping", fileName);
                    continue;
                }
                if (skillRepository.findByName(incoming.name()).isPresent()) {
                    log.debug("Skill '{}' is already stored — skipping", incoming.name());
                    continue;
                }
                skillRepository.save(incoming);
                log.info("Imported skill '{}'", incoming.name());
            } catch (Exception e) {
                log.warn("Failed to load skill from {}: {}", fileName, e.getMessage());
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
            // Strip the fields that are expected to differ: timestamps, and the
            // version, which counts saves — a seed never carries one.
            for (String field : List.of("createdAt", "updatedAt", "version")) {
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

    /** Reads a seed document. */
    private <T> T read(Resource resource, Class<T> type) throws java.io.IOException {
        return objectMapper.readerFor(type)
                .readValue(resource.getInputStream());
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
