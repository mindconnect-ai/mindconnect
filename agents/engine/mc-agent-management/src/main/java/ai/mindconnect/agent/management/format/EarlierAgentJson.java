package ai.mindconnect.agent.management.format;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import ai.mindconnect.agent.management.api.AgentId;
import ai.mindconnect.agent.management.api.ManagedAgent;
import ai.mindconnect.agent.management.tool.AgentToolBinding;
import ai.mindconnect.agent.runtime.api.domain.AgentDefinition;
import ai.mindconnect.agent.runtime.api.domain.ModelSettings;
import ai.mindconnect.agent.runtime.api.domain.ToolBinding;
import ai.mindconnect.agent.runtime.capabilities.memory.MemoryPlugin;
import ai.mindconnect.agent.runtime.capabilities.permission.PermissionPlugin;
import ai.mindconnect.agent.runtime.capabilities.review.ResponseReviewPlugin;
import ai.mindconnect.agent.runtime.capabilities.skill.SkillPlugin;
import ai.mindconnect.agent.runtime.capabilities.subagent.SubAgentPlugin;

/**
 * Reads an agent file of the form the earlier runtime wrote: its fields
 * flat, the prompt as {@code systemPrompt}, the model as
 * {@code llmConfigName}, a memory setting by {@code kind}. What the runtime
 * has a field for goes there; everything else goes into the definition's
 * metadata under the key of the capability that reads it.
 *
 * <p>Read from the JSON itself, not through the earlier runtime's classes:
 * this outlives them.
 */
final class EarlierAgentJson {

    private static final int DEFAULT_MAX_ROUNDS = 10;
    private static final int DEFAULT_WINDOW_SIZE = 20;
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() { };

    private EarlierAgentJson() {
    }

    static ManagedAgent read(JsonNode agent, ObjectMapper json) {
        String name = text(agent, "name");
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("An agent file names its agent");
        }
        String id = text(agent, "id") == null ? UUID.randomUUID().toString() : text(agent, "id");
        Map<String, Object> metadata = new HashMap<>();
        metadata.put(ManagedAgent.AGENT_ID, id);
        metadata.put(SubAgentPlugin.SUB_AGENTS, names(agent.get("callableAgents")));
        if (!agent.path(SubAgentPlugin.CALLABLE).asBoolean(true)) {
            metadata.put(SubAgentPlugin.CALLABLE, false);
        }
        List<String> reviewers = names(agent.get("responseReviewers"));
        if (!reviewers.isEmpty()) {
            metadata.put(ResponseReviewPlugin.REVIEWERS, reviewers);
        }
        skills(agent.get("skills"), metadata);
        if (agent.hasNonNull("memoryConfig")) {
            metadata.put(MemoryPlugin.MEMORY, memory(agent.get("memoryConfig")));
        }

        List<ToolBinding> tools = new ArrayList<>();
        for (JsonNode tool : agent.path("tools")) {
            // A tool the agent had switched off is one it does not have.
            if (tool.path("enabled").asBoolean(true)) {
                tools.add(binding(tool, json));
            }
        }
        int rounds = agent.path("maxIterations").asInt(0);
        String model = text(agent, "llmConfigName");
        AgentDefinition definition = new AgentDefinition(
                name,
                text(agent, "description") == null ? "" : text(agent, "description"),
                text(agent, "systemPrompt") == null ? "" : text(agent, "systemPrompt"),
                // The name of an LLM configuration is the model's name to the gateway.
                model == null ? ModelSettings.NONE : ModelSettings.ofModel(model),
                tools,
                rounds > 0 ? rounds : DEFAULT_MAX_ROUNDS,
                metadata);
        return new ManagedAgent(AgentId.of(id), definition, text(agent, "group"), text(agent, "icon"),
                text(agent, "welcomeMessage"),
                text(agent, "status") == null ? ManagedAgent.Status.ACTIVE
                        : ManagedAgent.Status.valueOf(text(agent, "status").toUpperCase(Locale.ROOT)),
                instant(agent, "createdAt"), instant(agent, "updatedAt"), agent.path("version").asLong(0));
    }

    private static ToolBinding binding(JsonNode tool, ObjectMapper json) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put(AgentToolBinding.TOOL_ID,
                text(tool, "id") == null ? UUID.randomUUID().toString() : text(tool, "id"));
        metadata.put(AgentToolBinding.OVERRIDES,
                tool.hasNonNull("overrides") ? json.convertValue(tool.get("overrides"), MAP) : Map.of());
        if (text(tool, "description") != null) {
            metadata.put(AgentToolBinding.DESCRIPTION, text(tool, "description"));
        }
        if (tool.hasNonNull("maxResultChars")) {
            metadata.put(AgentToolBinding.MAX_RESULT_CHARS, tool.get("maxResultChars").asInt());
        }
        if (tool.path("needsApproval").asBoolean(false)) {
            metadata.put(PermissionPlugin.NEEDS_APPROVAL, true);
        }
        return new ToolBinding(text(tool, "name"), tool.path("deferred").asBoolean(false), metadata);
    }

    /**
     * Every skill is no entry at all, none is an empty list, and the ones
     * named are the list. The switch that came before the mode is still
     * read: on with names is the ones named, on without is every skill, off
     * is none.
     */
    private static void skills(JsonNode skills, Map<String, Object> metadata) {
        if (skills == null || skills.isNull()) {
            return;
        }
        List<String> names = names(skills.get("names"));
        String mode = skills.hasNonNull("mode") ? skills.get("mode").asText()
                : !skills.hasNonNull("enabled") ? "ALL"
                : !skills.get("enabled").asBoolean() ? "NONE"
                : names.isEmpty() ? "ALL" : "SPECIFIC";
        switch (mode) {
            case "NONE" -> metadata.put(SkillPlugin.SKILLS, List.of());
            case "SPECIFIC" -> metadata.put(SkillPlugin.SKILLS, names);
            default -> { }
        }
    }

    /** The five strategies of the earlier runtime as the four of this one. */
    private static Map<String, Object> memory(JsonNode config) {
        Map<String, Object> memory = new HashMap<>();
        switch (config.path("kind").asText("")) {
            case "full" -> memory.put("strategy", "full");
            case "none" -> memory.put("strategy", "none");
            case "windowed" -> {
                memory.put("strategy", "window");
                memory.put("maxMessages", config.path("windowSize").asInt(DEFAULT_WINDOW_SIZE));
            }
            case "summarizing_window" -> {
                memory.put("strategy", config.path("autoSummarize").asBoolean(false) ? "compact" : "window");
                fitted(config, "maxConversationRatio", memory);
            }
            case "auto_compact" -> {
                memory.put("strategy", "compact");
                fitted(config, "compactAtRatio", memory);
            }
            default -> throw new IllegalArgumentException(
                    "There is no memory setting of kind '" + config.path("kind").asText("") + "'");
        }
        return memory;
    }

    private static void fitted(JsonNode config, String ratio, Map<String, Object> memory) {
        if (config.hasNonNull(ratio)) {
            memory.put("maxConversationRatio", config.get(ratio).asDouble());
        }
        if (config.hasNonNull("summaryPlacement")) {
            memory.put("summaryPlacement", config.get("summaryPlacement").asText().toLowerCase(Locale.ROOT));
        }
    }

    private static List<String> names(JsonNode list) {
        List<String> names = new ArrayList<>();
        if (list != null && list.isArray()) {
            list.forEach(name -> names.add(name.asText()));
        }
        return List.copyOf(names);
    }

    private static String text(JsonNode node, String field) {
        return node.hasNonNull(field) ? node.get(field).asText() : null;
    }

    private static Instant instant(JsonNode node, String field) {
        return text(node, field) == null ? null : Instant.parse(text(node, field));
    }
}
