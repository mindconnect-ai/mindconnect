package ai.mindconnect.agent.domain;

import ai.mindconnect.agent.tool.AgentTool;
import ai.mindconnect.agent.tool.Tool;
import ai.mindconnect.agent.memory.domain.MemoryConfig;
import ai.mindconnect.agent.memory.domain.SummarizingWindowConfig;
import ai.mindconnect.common.Namespace;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record AgentDefinition(
        UUID id,
        Namespace namespace,
        String name,
        String description,
        String systemPrompt,
        String welcomeMessage,
        String llmConfigName,
        int maxIterations,
        MemoryConfig memoryConfig,
        AgentDefinitionStatus status,
        List<AgentTool> tools,
        /**
         * Names of stateless sub-agents that get to inspect / modify / block this
         * agent's final response, in order. Each reviewer receives the user's question
         * and the current draft answer as template variables ({@code user_message},
         * {@code agent_response}) and returns a plain-text replacement. If the
         * response begins with {@code BLOCK:}, the rest is shown to the user
         * instead of the original answer and remaining reviewers are skipped.
         * <p>
         * {@code null} or empty = no reviewers run.
         */
        List<String> responseReviewers,
        /**
         * Agent-level tool-search setting. {@code null} (older persisted
         * agents) means disabled. When enabled, the runtime injects the
         * {@code tool_search} tool automatically; its search space is the
         * agent's deferred tools plus the registry groups listed here
         * ({@code "*"} = every group).
         */
        ToolSearchConfig toolSearch,
        Instant createdAt,
        Instant updatedAt
) {

    /** Tool-search switch + registry-group filter, stored with the agent. */
    public record ToolSearchConfig(boolean enabled, List<String> groups) {
        public ToolSearchConfig {
            if (groups == null) groups = List.of();
        }

        public static final ToolSearchConfig OFF = new ToolSearchConfig(false, List.of());
    }

    /** Pre-tool-search constructor: search disabled. */
    public AgentDefinition(UUID id, Namespace namespace, String name, String description,
                           String systemPrompt, String welcomeMessage, String llmConfigName,
                           int maxIterations, MemoryConfig memoryConfig, AgentDefinitionStatus status,
                           List<AgentTool> tools, List<String> responseReviewers,
                           Instant createdAt, Instant updatedAt) {
        this(id, namespace, name, description, systemPrompt, welcomeMessage, llmConfigName,
                maxIterations, memoryConfig, status, tools, responseReviewers, null,
                createdAt, updatedAt);
    }

    /** Never {@code null}: older agents without the field read as OFF. */
    public ToolSearchConfig toolSearchOrOff() {
        return toolSearch == null ? ToolSearchConfig.OFF : toolSearch;
    }

    public static AgentDefinition create(Namespace namespace, String name, String description,
                                         String systemPrompt, String welcomeMessage,
                                         String llmConfigName) {
        Instant now = Instant.now();
        return new AgentDefinition(UUID.randomUUID(), namespace, name, description,
                systemPrompt, welcomeMessage, llmConfigName,
                10, SummarizingWindowConfig.DEFAULT,
                AgentDefinitionStatus.ACTIVE, List.of(), List.of(), now, now);
    }

    /** Returns the configured memory config, or the system default if none is set. */
    public MemoryConfig effectiveMemoryConfig() {
        return memoryConfig != null ? memoryConfig : SummarizingWindowConfig.DEFAULT;
    }

    /** Returns the configured reviewers list, or empty if none is set. */
    public List<String> effectiveResponseReviewers() {
        return responseReviewers != null ? responseReviewers : List.of();
    }

    /** Replaces the tool-search setting (see {@link ToolSearchConfig}). */
    public AgentDefinition withToolSearch(ToolSearchConfig toolSearch) {
        return new AgentDefinition(id, namespace, name, description, systemPrompt, welcomeMessage,
                llmConfigName, maxIterations, memoryConfig,
                status, tools, responseReviewers, toolSearch, createdAt, Instant.now());
    }

    public AgentDefinition withMemoryConfig(MemoryConfig memoryConfig) {
        return new AgentDefinition(id, namespace, name, description, systemPrompt, welcomeMessage,
                llmConfigName, maxIterations, memoryConfig, status, tools, responseReviewers,
                toolSearch, createdAt, Instant.now());
    }

    public AgentDefinition withTools(List<AgentTool> tools) {
        return new AgentDefinition(id, namespace, name, description, systemPrompt, welcomeMessage,
                llmConfigName, maxIterations, memoryConfig,
                status, tools, responseReviewers, toolSearch, createdAt, Instant.now());
    }

    public AgentDefinition withBasicFields(String name, String description, String systemPrompt,
                                           String welcomeMessage, String llmConfigName) {
        return new AgentDefinition(id, namespace, name, description, systemPrompt, welcomeMessage,
                llmConfigName, maxIterations, memoryConfig,
                status, tools, responseReviewers, toolSearch, createdAt, Instant.now());
    }

    public AgentDefinition withBasicFields(Namespace namespace, String name, String description,
                                           String systemPrompt, String welcomeMessage,
                                           String llmConfigName, List<String> responseReviewers) {
        return new AgentDefinition(id, namespace, name, description, systemPrompt, welcomeMessage,
                llmConfigName, maxIterations, memoryConfig,
                status, tools, responseReviewers, toolSearch, createdAt, Instant.now());
    }

    /**
     * Same as the other {@code withBasicFields} but also replaces
     * {@link #maxIterations}. Used by the admin-ui edit form, which
     * exposes the field directly. Kept separate from the older overloads
     * so callers that don't care about iterations don't have to think
     * about a sensible default.
     */
    public AgentDefinition withBasicFields(Namespace namespace, String name, String description,
                                           String systemPrompt, String welcomeMessage,
                                           String llmConfigName, int maxIterations,
                                           List<String> responseReviewers) {
        return new AgentDefinition(id, namespace, name, description, systemPrompt, welcomeMessage,
                llmConfigName, maxIterations, memoryConfig,
                status, tools, responseReviewers, toolSearch, createdAt, Instant.now());
    }

    public AgentDefinition asCopy() {
        Instant now = Instant.now();
        return new AgentDefinition(UUID.randomUUID(), namespace, name + "-copy", description,
                systemPrompt, welcomeMessage, llmConfigName, maxIterations, memoryConfig,
                AgentDefinitionStatus.ACTIVE, List.of(), responseReviewers, toolSearch, now, now);
    }
}
