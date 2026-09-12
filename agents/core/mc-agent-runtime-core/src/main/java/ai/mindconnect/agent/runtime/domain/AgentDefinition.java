package ai.mindconnect.agent.runtime.domain;

import ai.mindconnect.agent.AgentId;
import ai.mindconnect.agent.tool.AgentTool;
import ai.mindconnect.agent.runtime.memory.domain.MemoryConfig;
import ai.mindconnect.agent.runtime.memory.domain.SummarizingWindowConfig;

import java.time.Instant;
import java.util.List;

public record AgentDefinition(
        AgentId id,
        String name,
        String description,
        /**
         * The rubric this agent is filed under in catalogs and pickers — the
         * same idea as {@code ToolFactory.group()}, except that an agent is
         * configuration, so it carries its group as data instead of declaring
         * it in code. A free string; the seeds use {@code assistants} for the
         * agents a person chats with, {@code sub-agents} for the specialists
         * they delegate to, and {@code utilities} for the ones the runtime
         * calls on its own. {@code null} or blank reads as {@code general}.
         */
        String group,
        /**
         * Lucide icon name for this agent — the id of a symbol in the
         * framework's sprite ({@code /sui/icons.svg}), e.g. {@code telescope}
         * or {@code bot}. Shown wherever the agent is named: the admin list,
         * the chat header and the chat history. {@code null} means the caller
         * picks its own default for the surface it draws.
         */
        String icon,
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
         * The other agents this one may see and call, by name — the roster it
         * delegates to. {@code null} or empty means no restriction: it sees
         * every agent, which is what an agent that was never
         * given a roster has always done.
         *
         * <p>Governs both halves of delegating, because half of it would be
         * theatre: {@code list_agents} returns only these, and a
         * {@code run_agent} for anything else is refused. Filtering the list
         * alone would leave a model free to call a name it read in its own
         * prompt.
         */
        List<String> callableAgents,
        /**
         * Agent-level tool-search setting. {@code null} (older persisted
         * agents) means disabled. When enabled, the runtime injects the
         * {@code tool_search} tool automatically; its search space is the
         * agent's deferred tools plus the registry groups listed here
         * ({@code "*"} = every group).
         */
        ToolSearchConfig toolSearch,
        /**
         * Agent-level skills setting. {@code null} (older persisted agents)
         * means disabled. When enabled, the runtime injects the {@code skill}
         * tool and lists the agent's skills in the system prompt; the names
         * listed here narrow that to those skills, and an empty list leaves
         * every skill of the installation, the user and the project open.
         */
        SkillsConfig skills,
        Instant createdAt,
        Instant updatedAt,
        /**
         * The stored version this definition was read with — optimistic locking
         * for edits made as a whole (the admin form, a REST update). A store saves
         * it only if it is still the stored version and stores it one higher;
         * {@code null} saves without that check. See {@code ai.mindconnect.common.Versions}.
         */
        Long version
) {

    /**
     * Group and icon are normalised on the way in — trimmed, lower-cased,
     * blank read as absent. Both are machine names, and both are typed by
     * hand: without this, "Assistants" from the form would file an agent under
     * a second rubric that renders under the same heading as "assistants", and
     * "Telescope" would look up a sprite symbol that does not exist (the ids
     * are lower-case) and draw nothing at all.
     */
    public AgentDefinition {
        group = normalisedName(group);
        icon = normalisedName(icon);
    }

    /**
     * Trim, fold case, and read blank as absent — for the two machine names.
     */
    private static String normalisedName(String value) {
        if (value == null) return null;
        String v = value.trim().toLowerCase(java.util.Locale.ROOT);
        return v.isEmpty() ? null : v;
    }

    /**
     * Tool-search switch + registry-group filter, stored with the agent.
     */
    public record ToolSearchConfig(boolean enabled, List<String> groups) {
        public ToolSearchConfig {
            if (groups == null) groups = List.of();
        }

        public static final ToolSearchConfig OFF = new ToolSearchConfig(false, List.of());
    }

    /**
     * Skills switch + the names it is narrowed to, stored with the agent.
     */
    public record SkillsConfig(boolean enabled, List<String> names) {
        public SkillsConfig {
            if (names == null) names = List.of();
        }

        public static final SkillsConfig OFF = new SkillsConfig(false, List.of());

        /** Every skill this installation, user and project have. */
        public static SkillsConfig all() {
            return new SkillsConfig(true, List.of());
        }
    }

    /**
     * Without a version: the definition saves without a version check.
     */
    public AgentDefinition(AgentId id, String name, String description, String group, String icon,
                           String systemPrompt, String welcomeMessage, String llmConfigName,
                           int maxIterations, MemoryConfig memoryConfig, AgentDefinitionStatus status,
                           List<AgentTool> tools, List<String> responseReviewers, List<String> callableAgents,
                           ToolSearchConfig toolSearch, SkillsConfig skills,
                           Instant createdAt, Instant updatedAt) {
        this(id, name, description, group, icon, systemPrompt, welcomeMessage, llmConfigName,
                maxIterations, memoryConfig, status, tools, responseReviewers, callableAgents, toolSearch,
                skills, createdAt, updatedAt, null);
    }

    /**
     * Pre-skills constructor: skills off.
     */
    public AgentDefinition(AgentId id, String name, String description, String group, String icon,
                           String systemPrompt, String welcomeMessage, String llmConfigName,
                           int maxIterations, MemoryConfig memoryConfig, AgentDefinitionStatus status,
                           List<AgentTool> tools, List<String> responseReviewers, List<String> callableAgents,
                           ToolSearchConfig toolSearch, Instant createdAt, Instant updatedAt) {
        this(id, name, description, group, icon, systemPrompt, welcomeMessage, llmConfigName,
                maxIterations, memoryConfig, status, tools, responseReviewers, callableAgents, toolSearch,
                null, createdAt, updatedAt, null);
    }

    /** This definition as read with, or to be saved against, {@code version} — nothing else changes. */
    public AgentDefinition withVersion(Long version) {
        return new AgentDefinition(id, name, description, group, icon, systemPrompt, welcomeMessage,
                llmConfigName, maxIterations, memoryConfig, status, tools, responseReviewers,
                callableAgents, toolSearch, skills, createdAt, updatedAt, version);
    }

    /**
     * Pre-tool-search constructor: search disabled.
     */
    public AgentDefinition(AgentId id, String name, String description,
                           String systemPrompt, String welcomeMessage, String llmConfigName,
                           int maxIterations, MemoryConfig memoryConfig, AgentDefinitionStatus status,
                           List<AgentTool> tools, List<String> responseReviewers,
                           Instant createdAt, Instant updatedAt) {
        this(id, name, description, null, null, systemPrompt, welcomeMessage, llmConfigName,
                maxIterations, memoryConfig, status, tools, responseReviewers, null, null,
                createdAt, updatedAt);
    }

    /**
     * Never {@code null}: older agents without the field read as OFF.
     */
    public ToolSearchConfig toolSearchOrOff() {
        return toolSearch == null ? ToolSearchConfig.OFF : toolSearch;
    }

    /**
     * Never {@code null}: older agents without the field read as OFF.
     */
    public SkillsConfig skillsOrOff() {
        return skills == null ? SkillsConfig.OFF : skills;
    }

    /**
     * Replaces the skills setting (see {@link SkillsConfig}).
     */
    public AgentDefinition withSkills(SkillsConfig skills) {
        return new AgentDefinition(id, name, description, group, icon, systemPrompt, welcomeMessage,
                llmConfigName, maxIterations, memoryConfig,
                status, tools, responseReviewers, callableAgents, toolSearch, skills, createdAt, Instant.now(), version);
    }

    public static AgentDefinition create(String name, String description,
                                         String systemPrompt, String welcomeMessage,
                                         String llmConfigName) {
        Instant now = Instant.now();
        return new AgentDefinition(AgentId.random(), name, description,
                systemPrompt, welcomeMessage, llmConfigName,
                10, SummarizingWindowConfig.DEFAULT,
                AgentDefinitionStatus.ACTIVE, List.of(), List.of(), now, now);
    }

    /**
     * Returns the configured memory config, or the system default if none is set.
     */
    public MemoryConfig effectiveMemoryConfig() {
        return memoryConfig != null ? memoryConfig : SummarizingWindowConfig.DEFAULT;
    }

    /**
     * Returns the configured reviewers list, or empty if none is set.
     */
    public List<String> effectiveResponseReviewers() {
        return responseReviewers != null ? responseReviewers : List.of();
    }

    /**
     * The default rubric for an agent that names none — matches the tool registry's.
     */
    public static final String DEFAULT_GROUP = "general";

    /**
     * Never {@code null} or blank: an agent filed under nothing is filed under general.
     */
    public String groupOrDefault() {
        return group == null || group.isBlank() ? DEFAULT_GROUP : group;
    }

    /**
     * The icon an agent gets when it names none — it still has to read as an agent.
     */
    public static final String DEFAULT_ICON = "bot";

    /**
     * Never {@code null} or blank. Every surface that draws an agent — the
     * admin list, the chat header, the chat history — falls back through this
     * one method, so an agent without an icon looks the same everywhere.
     */
    public String iconOrDefault() {
        return icon == null || icon.isBlank() ? DEFAULT_ICON : icon;
    }

    /**
     * The roster as a list, empty when the agent may reach everything.
     */
    public List<String> effectiveCallableAgents() {
        return callableAgents != null ? callableAgents : List.of();
    }

    /**
     * Whether this agent may see and call the named one. An empty roster is
     * no restriction, not a ban — an agent that names nobody reaches everyone,
     * which is how every agent behaved before the field existed.
     *
     * <p>The comparison ignores case, like the name lookup a sub-agent call
     * does: a roster entry that differs only in case would otherwise pass the
     * lookup and fail this check.
     */
    public boolean mayCall(String agentName) {
        List<String> roster = effectiveCallableAgents();
        if (roster.isEmpty()) return true;
        return agentName != null && roster.stream().anyMatch(agentName::equalsIgnoreCase);
    }

    /**
     * Replaces the roster of agents this one may see and call.
     */
    public AgentDefinition withCallableAgents(List<String> callableAgents) {
        return new AgentDefinition(id, name, description, group, icon, systemPrompt, welcomeMessage,
                llmConfigName, maxIterations, memoryConfig,
                status, tools, responseReviewers, callableAgents, toolSearch, skills, createdAt, Instant.now(), version);
    }

    /**
     * Replaces the Lucide icon name (see {@link #icon()}).
     */
    public AgentDefinition withIcon(String icon) {
        return new AgentDefinition(id, name, description, group, icon, systemPrompt, welcomeMessage,
                llmConfigName, maxIterations, memoryConfig,
                status, tools, responseReviewers, callableAgents, toolSearch, skills, createdAt, Instant.now(), version);
    }

    /**
     * Refiles the agent under another rubric.
     */
    public AgentDefinition withGroup(String group) {
        return new AgentDefinition(id, name, description, group, icon, systemPrompt, welcomeMessage,
                llmConfigName, maxIterations, memoryConfig,
                status, tools, responseReviewers, callableAgents, toolSearch, skills, createdAt, Instant.now(), version);
    }

    /**
     * Replaces the tool-search setting (see {@link ToolSearchConfig}).
     */
    public AgentDefinition withToolSearch(ToolSearchConfig toolSearch) {
        return new AgentDefinition(id, name, description, group, icon, systemPrompt, welcomeMessage,
                llmConfigName, maxIterations, memoryConfig,
                status, tools, responseReviewers, callableAgents, toolSearch, skills, createdAt, Instant.now(), version);
    }

    public AgentDefinition withMemoryConfig(MemoryConfig memoryConfig) {
        return new AgentDefinition(id, name, description, group, icon, systemPrompt, welcomeMessage,
                llmConfigName, maxIterations, memoryConfig, status, tools, responseReviewers,
                callableAgents, toolSearch, skills, createdAt, Instant.now(), version);
    }

    public AgentDefinition withTools(List<AgentTool> tools) {
        return new AgentDefinition(id, name, description, group, icon, systemPrompt, welcomeMessage,
                llmConfigName, maxIterations, memoryConfig,
                status, tools, responseReviewers, callableAgents, toolSearch, skills, createdAt, Instant.now(), version);
    }

    public AgentDefinition withBasicFields(String name, String description, String systemPrompt,
                                           String welcomeMessage, String llmConfigName) {
        return new AgentDefinition(id, name, description, group, icon, systemPrompt, welcomeMessage,
                llmConfigName, maxIterations, memoryConfig,
                status, tools, responseReviewers, callableAgents, toolSearch, skills, createdAt, Instant.now(), version);
    }

    public AgentDefinition withBasicFields(String name, String description,
                                           String systemPrompt, String welcomeMessage,
                                           String llmConfigName, List<String> responseReviewers) {
        return new AgentDefinition(id, name, description, group, icon, systemPrompt, welcomeMessage,
                llmConfigName, maxIterations, memoryConfig,
                status, tools, responseReviewers, callableAgents, toolSearch, skills, createdAt, Instant.now(), version);
    }

    /**
     * Same as the other {@code withBasicFields} but also replaces
     * {@link #maxIterations}. Used by the admin-ui edit form, which
     * exposes the field directly. Kept separate from the older overloads
     * so callers that don't care about iterations don't have to think
     * about a sensible default.
     */
    public AgentDefinition withBasicFields(String name, String description,
                                           String systemPrompt, String welcomeMessage,
                                           String llmConfigName, int maxIterations,
                                           List<String> responseReviewers) {
        return new AgentDefinition(id, name, description, group, icon, systemPrompt, welcomeMessage,
                llmConfigName, maxIterations, memoryConfig,
                status, tools, responseReviewers, callableAgents, toolSearch, skills, createdAt, Instant.now(), version);
    }

    public AgentDefinition asCopy() {
        Instant now = Instant.now();
        return new AgentDefinition(AgentId.random(), name + "-copy", description, group, icon,
                systemPrompt, welcomeMessage, llmConfigName, maxIterations, memoryConfig,
                AgentDefinitionStatus.ACTIVE, List.of(), responseReviewers, callableAgents, toolSearch,
                skills, now, now);
    }
}
