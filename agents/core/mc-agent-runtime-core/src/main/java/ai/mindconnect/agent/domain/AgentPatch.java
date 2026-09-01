package ai.mindconnect.agent.domain;

import ai.mindconnect.agent.tool.AgentTool;
import ai.mindconnect.common.Namespace;

import java.util.List;
import java.util.Optional;

/**
 * Patch for updating an existing {@link AgentDefinition}.
 *
 * <p>Each field is {@link Optional}: an empty optional means "do not change",
 * a present optional means "set this field to the given value". This makes
 * partial updates explicit and avoids ambiguity with {@code null}.
 *
 * <p>Covers everything the admin UI's edit form can change — including
 * {@code maxIterations}, {@code responseReviewers}, {@code toolSearch} and
 * the owning {@code namespace} — so both the UI and the external REST API
 * update agents through {@code AgentRegistryService.update} alone.
 *
 * <p>Build with {@link #of()} plus the with-ers; the canonical constructor
 * stays available for exhaustive call sites. Validation lives in the
 * use-case ({@code AgentRegistry.update}), not in this record.
 */
public record AgentPatch(
        Optional<Namespace> namespace,
        Optional<String> name,
        Optional<String> description,
        Optional<String> group,
        Optional<String> icon,
        Optional<String> systemPrompt,
        Optional<String> welcomeMessage,
        Optional<String> llmConfigName,
        Optional<Integer> maxIterations,
        Optional<List<String>> responseReviewers,
        Optional<List<String>> callableAgents,
        Optional<AgentDefinition.ToolSearchConfig> toolSearch,
        Optional<List<AgentTool>> tools,
        Optional<ai.mindconnect.agent.memory.domain.MemoryConfig> memoryConfig
) {

    /** A patch that changes nothing — the starting point for the with-ers. */
    public static AgentPatch of() {
        return new AgentPatch(Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty());
    }

    public AgentPatch withNamespace(Namespace namespace) {
        return new AgentPatch(Optional.ofNullable(namespace), name, description, group, icon, systemPrompt,
                welcomeMessage, llmConfigName, maxIterations, responseReviewers, callableAgents, toolSearch, tools, memoryConfig);
    }

    public AgentPatch withName(String name) {
        return new AgentPatch(namespace, Optional.ofNullable(name), description, group, icon, systemPrompt,
                welcomeMessage, llmConfigName, maxIterations, responseReviewers, callableAgents, toolSearch, tools, memoryConfig);
    }

    public AgentPatch withDescription(String description) {
        return new AgentPatch(namespace, name, Optional.ofNullable(description), group, icon, systemPrompt,
                welcomeMessage, llmConfigName, maxIterations, responseReviewers, callableAgents, toolSearch, tools, memoryConfig);
    }

    public AgentPatch withGroup(String group) {
        return new AgentPatch(namespace, name, description, Optional.ofNullable(group), icon, systemPrompt,
                welcomeMessage, llmConfigName, maxIterations, responseReviewers, callableAgents, toolSearch, tools, memoryConfig);
    }

    public AgentPatch withIcon(String icon) {
        return new AgentPatch(namespace, name, description, group, Optional.ofNullable(icon), systemPrompt,
                welcomeMessage, llmConfigName, maxIterations, responseReviewers, callableAgents, toolSearch, tools, memoryConfig);
    }

    public AgentPatch withSystemPrompt(String systemPrompt) {
        return new AgentPatch(namespace, name, description, group, icon, Optional.ofNullable(systemPrompt),
                welcomeMessage, llmConfigName, maxIterations, responseReviewers, callableAgents, toolSearch, tools, memoryConfig);
    }

    public AgentPatch withWelcomeMessage(String welcomeMessage) {
        return new AgentPatch(namespace, name, description, group, icon, systemPrompt,
                Optional.ofNullable(welcomeMessage), llmConfigName, maxIterations,
                responseReviewers, callableAgents, toolSearch, tools, memoryConfig);
    }

    public AgentPatch withLlmConfigName(String llmConfigName) {
        return new AgentPatch(namespace, name, description, group, icon, systemPrompt, welcomeMessage,
                Optional.ofNullable(llmConfigName), maxIterations, responseReviewers, callableAgents, toolSearch, tools, memoryConfig);
    }

    public AgentPatch withMaxIterations(Integer maxIterations) {
        return new AgentPatch(namespace, name, description, group, icon, systemPrompt, welcomeMessage,
                llmConfigName, Optional.ofNullable(maxIterations), responseReviewers, callableAgents, toolSearch, tools, memoryConfig);
    }

    public AgentPatch withResponseReviewers(List<String> responseReviewers) {
        return new AgentPatch(namespace, name, description, group, icon, systemPrompt, welcomeMessage,
                llmConfigName, maxIterations, Optional.ofNullable(responseReviewers), callableAgents, toolSearch, tools, memoryConfig);
    }

    public AgentPatch withCallableAgents(List<String> callableAgents) {
        return new AgentPatch(namespace, name, description, group, icon, systemPrompt, welcomeMessage,
                llmConfigName, maxIterations, responseReviewers, Optional.ofNullable(callableAgents),
                toolSearch, tools, memoryConfig);
    }

    public AgentPatch withToolSearch(AgentDefinition.ToolSearchConfig toolSearch) {
        return new AgentPatch(namespace, name, description, group, icon, systemPrompt, welcomeMessage,
                llmConfigName, maxIterations, responseReviewers, callableAgents,
                Optional.ofNullable(toolSearch), tools, memoryConfig);
    }

    public AgentPatch withTools(List<AgentTool> tools) {
        return new AgentPatch(namespace, name, description, group, icon, systemPrompt, welcomeMessage,
                llmConfigName, maxIterations, responseReviewers, callableAgents, toolSearch, Optional.ofNullable(tools), memoryConfig);
    }

    public AgentPatch withMemoryConfig(ai.mindconnect.agent.memory.domain.MemoryConfig memoryConfig) {
        return new AgentPatch(namespace, name, description, group, icon, systemPrompt, welcomeMessage,
                llmConfigName, maxIterations, responseReviewers, callableAgents, toolSearch, tools,
                Optional.ofNullable(memoryConfig));
    }
}
