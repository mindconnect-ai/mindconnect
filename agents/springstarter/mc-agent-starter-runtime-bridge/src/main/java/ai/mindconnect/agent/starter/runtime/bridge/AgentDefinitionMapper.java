package ai.mindconnect.agent.starter.runtime.bridge;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import ai.mindconnect.agent.runtime.api.domain.AgentDefinition;
import ai.mindconnect.agent.runtime.api.domain.ModelSettings;
import ai.mindconnect.agent.runtime.api.domain.ToolBinding;
import ai.mindconnect.agent.runtime.capabilities.review.ResponseReviewPlugin;
import ai.mindconnect.agent.runtime.capabilities.memory.MemoryPlugin;
import ai.mindconnect.agent.runtime.capabilities.permission.PermissionPlugin;
import ai.mindconnect.agent.runtime.capabilities.skill.SkillPlugin;
import ai.mindconnect.agent.runtime.capabilities.subagent.SubAgentPlugin;
import ai.mindconnect.agent.runtime.memory.domain.AutoCompactConfig;
import ai.mindconnect.agent.runtime.memory.domain.FullHistoryMemoryConfig;
import ai.mindconnect.agent.runtime.memory.domain.MemoryConfig;
import ai.mindconnect.agent.runtime.memory.domain.NoMemoryConfig;
import ai.mindconnect.agent.runtime.memory.domain.WindowedMemoryConfig;
import ai.mindconnect.agent.runtime.memory.domain.SummarizingWindowConfig;
import ai.mindconnect.agent.tool.AgentTool;

/**
 * An agent definition as the application stores it, as the definition the
 * runtime runs. The stored one is what the administration pages edit; the
 * runtime's is what a session and a turn carry.
 *
 * <p>What the runtime has a field for goes there. Everything else goes into
 * metadata under the key of the capability that reads it: the agents it may
 * call, how its history is fitted, and per tool what this agent set for it.
 */
public final class AgentDefinitionMapper {

    /** Metadata of the agent: its id in the application's repository. */
    public static final String AGENT_ID = "id";

    private static final int DEFAULT_MAX_ROUNDS = 10;

    public AgentDefinition toRuntime(ai.mindconnect.agent.runtime.domain.AgentDefinition stored) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put(AGENT_ID, stored.id().value());
        metadata.put(SubAgentPlugin.SUB_AGENTS,
                stored.callableAgents() == null ? List.of() : List.copyOf(stored.callableAgents()));
        if (!stored.effectiveResponseReviewers().isEmpty()) {
            metadata.put(ResponseReviewPlugin.REVIEWERS, List.copyOf(stored.effectiveResponseReviewers()));
        }
        skills(stored.skills()).ifPresent(picked -> metadata.put(SkillPlugin.SKILLS, picked));
        if (stored.memoryConfig() != null) {
            metadata.put(MemoryPlugin.MEMORY, memory(stored.memoryConfig()));
        }
        return new AgentDefinition(
                stored.name(),
                stored.description() == null ? "" : stored.description(),
                stored.systemPrompt() == null ? "" : stored.systemPrompt(),
                // The name of an LLM configuration is the model's name to the gateway.
                stored.llmConfigName() == null ? ModelSettings.NONE : ModelSettings.ofModel(stored.llmConfigName()),
                stored.tools().stream().map(AgentDefinitionMapper::binding).toList(),
                stored.maxIterations() > 0 ? stored.maxIterations() : DEFAULT_MAX_ROUNDS,
                metadata);
    }

    private static ToolBinding binding(AgentTool tool) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put(RegistryToolResolver.TOOL_ID, tool.id().value());
        metadata.put(RegistryToolResolver.OVERRIDES, tool.overrides());
        if (tool.description() != null) {
            metadata.put(RegistryToolResolver.DESCRIPTION, tool.description());
        }
        if (tool.maxResultChars() != null) {
            metadata.put(RegistryToolResolver.MAX_RESULT_CHARS, tool.maxResultChars());
        }
        if (!tool.enabled()) {
            metadata.put(ToolBinding.ENABLED, false);
        }
        if (tool.needsApproval()) {
            metadata.put(PermissionPlugin.NEEDS_APPROVAL, true);
        }
        return new ToolBinding(tool.name(), tool.deferred(), metadata);
    }

    /** Every skill is no entry at all, none is an empty list, and the ones named are the list. */
    private static Optional<List<String>> skills(
            ai.mindconnect.agent.runtime.domain.AgentDefinition.SkillsConfig skills) {
        if (skills == null) {
            return Optional.empty();
        }
        return switch (skills.mode()) {
            case ALL -> Optional.empty();
            case NONE -> Optional.of(List.of());
            case SPECIFIC -> Optional.of(skills.names());
        };
    }

    /** The five strategies of the earlier runtime as the four of this one. */
    private static Map<String, Object> memory(MemoryConfig config) {
        return switch (config) {
            case FullHistoryMemoryConfig full -> Map.of("strategy", "full");
            case SummarizingWindowConfig window -> Map.of(
                    "strategy", window.autoSummarize() ? "compact" : "window",
                    "maxConversationRatio", window.maxConversationRatio(),
                    "summaryPlacement", window.summaryPlacement().name().toLowerCase(Locale.ROOT));
            case AutoCompactConfig compact -> Map.of(
                    "strategy", "compact",
                    "maxConversationRatio", compact.compactAtRatio(),
                    "summaryPlacement", compact.summaryPlacement().name().toLowerCase(Locale.ROOT));
            case NoMemoryConfig none -> Map.of("strategy", "none");
            case WindowedMemoryConfig window -> Map.of("strategy", "window", "maxMessages", window.windowSize());
        };
    }
}
