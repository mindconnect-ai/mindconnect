package ai.mindconnect.cli;

import ai.mindconnect.agent.management.api.AgentManagement;
import ai.mindconnect.agent.management.api.ManagedAgent;
import ai.mindconnect.agent.management.format.AgentJson;
import ai.mindconnect.llm.domain.LlmConfig;
import ai.mindconnect.llm.port.out.LlmConfigRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Loads initial data from {@code initial-data/} on startup — from every jar
 * on the classpath, so a module that ships agents or skills of its own is
 * seeded like the bundled ones.
 * <p>
 * <ul>
 *   <li>{@code initial-data/llm-configs/*.json} — imported if no config with the same name exists;
 *       if the stored config differs from the classpath version the supplied {@link ConfirmOverwrite}
 *       callback is invoked and the record is overwritten only if it returns {@code true}.</li>
 *   <li>{@code initial-data/agent-definitions/*.json} — same semantics per name.</li>
 *   <li>{@code initial-data/skills/*.md} — one {@code SKILL.md} per skill, imported
 *       when no stored skill carries its name and never overwritten.</li>
 * </ul>
 * New records are always imported. Existing identical records are silently skipped.
 */
@Component
public class InitialDataLoader {

    private static final Logger log = LoggerFactory.getLogger(InitialDataLoader.class);

    /** Called when a stored entity differs from the classpath version. Return true to overwrite. */
    @FunctionalInterface
    public interface ConfirmOverwrite {
        boolean confirm(String entityType, String name, String diff);
    }

    private final LlmConfigRepository llmConfigRepository;
    private final AgentManagement agents;
    private final ai.mindconnect.agent.skill.port.out.SkillRepository skillRepository;
    private final ObjectMapper objectMapper;

    public InitialDataLoader(LlmConfigRepository llmConfigRepository,
                             AgentManagement agents,
                             ai.mindconnect.agent.skill.port.out.SkillRepository skillRepository,
                             ObjectMapper objectMapper) {
        this.llmConfigRepository = llmConfigRepository;
        this.agents = agents;
        this.skillRepository = skillRepository;
        this.objectMapper = objectMapper;
    }

    /** Reads a seed document. */
    private <T> T readSeed(Resource resource, Class<T> type) throws java.io.IOException {
        return objectMapper.readerFor(type)
                .readValue(resource.getInputStream());
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

    /**
     * Imports the shipped {@code SKILL.md} files the store does not already
     * know by name. No overwrite prompt, unlike the records above: a skill is
     * prose somebody has since made their own, and the shipped wording has no
     * claim on it.
     */
    private void loadSkills() {
        for (Resource resource : scan("classpath*:initial-data/skills/*.md")) {
            String fileName = resource.getFilename() == null ? "skill" : resource.getFilename();
            String fallback = fileName.endsWith(".md")
                    ? fileName.substring(0, fileName.length() - 3) : fileName;
            try {
                String content = new String(resource.getInputStream().readAllBytes(),
                        java.nio.charset.StandardCharsets.UTF_8);
                var incoming = ai.mindconnect.agent.skill.domain.Skill.fromMarkdown(fallback, content,
                        ai.mindconnect.agent.skill.domain.SkillSource.MANAGED, null);
                if (incoming == null) {
                    log.warn("Initial skill {} has no instructions, or a name that is not lower-case "
                            + "letters, digits and dashes — skipping", fileName);
                    continue;
                }
                if (skillRepository.findByName(incoming.name()).isEmpty()) {
                    skillRepository.save(incoming);
                    log.info("Imported skill '{}'", incoming.name());
                }
            } catch (Exception e) {
                log.warn("Failed to load skill from {}: {}", fileName, e.getMessage());
            }
        }
    }

    // ── LLM configs ───────────────────────────────────────────────────────────

    private void loadLlmConfigs(ConfirmOverwrite confirm) {
        for (Resource resource : scan("classpath*:initial-data/llm-configs/*.json")) {
            try {
                LlmConfig incoming = readSeed(resource, LlmConfig.class);
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
        AgentJson files = new AgentJson(objectMapper);
        for (Resource resource : scan("classpath*:initial-data/agent-definitions/*.json")) {
            try {
                ManagedAgent incoming = files.read(new String(resource.getInputStream().readAllBytes(),
                        java.nio.charset.StandardCharsets.UTF_8));
                String name = incoming.definition().name();
                agents.findByName(name).ifPresentOrElse(existing -> {
                    String diff = diffJson(comparable(existing), comparable(incoming));
                    if (diff == null) {
                        log.debug("Agent '{}' is up to date — skipping", name);
                    } else if (confirm.confirm("agent", name, diff)) {
                        // It stays the agent it is: its id, and so what refers to it, is kept.
                        agents.update(existing.id(), existing.version(), stored -> incoming);
                        log.info("Updated agent '{}'", name);
                    } else {
                        log.debug("Agent '{}' update skipped by user", name);
                    }
                }, () -> {
                    agents.create(incoming);
                    log.info("Imported agent '{}'", name);
                });
            } catch (Exception e) {
                log.warn("Failed to load agent definition from {}: {}", resource.getFilename(), e.getMessage());
            }
        }
    }

    /**
     * An agent as two versions of it are compared: without what tells two
     * installations apart — the id, also where the definition repeats it.
     */
    private com.fasterxml.jackson.databind.node.ObjectNode comparable(ManagedAgent agent) {
        com.fasterxml.jackson.databind.node.ObjectNode node = objectMapper.valueToTree(agent);
        node.remove("id");
        if (node.path("definition").path("metadata") instanceof com.fasterxml.jackson.databind.node.ObjectNode metadata) {
            metadata.remove(ManagedAgent.AGENT_ID);
        }
        return node;
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
