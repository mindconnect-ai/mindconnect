package ai.mindconnect.adminui.ui.controller;

import ai.mindconnect.adminui.ui.component.AgentFormComponent;
import ai.mindconnect.adminui.ui.component.AgentSetting;
import ai.mindconnect.agent.management.api.ManagedAgent;
import ai.mindconnect.agent.runtime.api.domain.AgentDefinition;
import ai.mindconnect.agent.runtime.api.domain.ModelSettings;
import ai.mindconnect.agent.runtime.capabilities.memory.MemoryPlugin;
import ai.mindconnect.agent.runtime.capabilities.review.ResponseReviewPlugin;
import ai.mindconnect.agent.runtime.capabilities.skill.SkillPlugin;
import ai.mindconnect.agent.runtime.capabilities.subagent.SubAgentPlugin;
import ai.mindconnect.agent.runtime.capabilities.toolresult.ToolResultPlugin;
import ai.mindconnect.agent.tool.AgentTool;
import ai.mindconnect.agent.tool.AgentToolId;
import ai.mindconnect.chatui.ui.controller.FormBody;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Reads the agent form and the tool form: what was submitted, as the agent
 * or the tool to save. What a setting means in an agent's definition is each
 * capability's own business; this only says which field feeds which setting.
 */
final class AgentForm {

    private final ObjectMapper mapper;

    AgentForm(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * The agent with what the form says. A field the form did not send is
     * left as it is; the tools are not the form's.
     *
     * @throws IllegalArgumentException for a memory setting that is no JSON
     *         object or names a strategy there is none of
     */
    ManagedAgent agent(ManagedAgent agent, FormBody body) {
        AgentDefinition stored = agent.definition();
        AgentDefinition settings = SubAgentPlugin.withSubAgents(stored, body.strList("callableAgents"));
        settings = SubAgentPlugin.withCallable(settings,
                body.bool(SubAgentPlugin.CALLABLE, SubAgentPlugin.callable(stored)));
        settings = ResponseReviewPlugin.withReviewers(settings, body.strList("responseReviewers"));
        // Blank is the default page: no entry, so a later default reaches this agent too.
        Integer page = body.numOrNull(AgentFormComponent.MAX_TOOL_RESULT_CHARS);
        if (page != null && page > 0) {
            settings = settings.with(ToolResultPlugin.MAX_RESULT_CHARS, page);
        } else if (body.str(AgentFormComponent.MAX_TOOL_RESULT_CHARS) != null) {
            settings = settings.with(ToolResultPlugin.MAX_RESULT_CHARS, null);
        }
        settings = switch (AgentFormComponent.skillsMode(body.str(AgentFormComponent.SKILLS_MODE))) {
            case AgentSetting.NONE -> SkillPlugin.withSkills(settings, List.of());
            case AgentSetting.SPECIFIC -> SkillPlugin.withSkills(settings, body.strList("skills"));
            default -> SkillPlugin.withEverySkill(settings);
        };
        Optional<Map<String, Object>> memory = memory(body);
        if (memory.isPresent()) {
            settings = settings.with(MemoryPlugin.MEMORY, memory.get());
        }
        ModelSettings model = stored.modelSettings();
        String llm = body.str("llmConfigName");
        AgentDefinition definition = new AgentDefinition(
                sent(body.str("name"), stored.name()),
                sent(body.str("description"), stored.description()),
                sent(body.str("systemPrompt"), stored.instructions()),
                // The name of an LLM configuration is the model's name to the gateway.
                llm == null ? model : new ModelSettings(llm.isBlank() ? null : llm, model.temperature(),
                        model.maxOutputTokens(), model.reasoningEffort(), model.extra()),
                stored.tools(),
                body.num("maxIterations", stored.maxRounds()),
                settings.metadata());
        String group = body.str("group");
        String icon = body.str("icon");
        return agent.withDefinition(definition).shownAs(
                group == null ? agent.group() : AgentSetting.machineName(group),
                icon == null ? agent.icon() : AgentSetting.machineName(icon),
                sent(body.str("welcomeMessage"), agent.welcomeMessage()));
    }

    private static String sent(String value, String otherwise) {
        return value == null ? otherwise : value;
    }

    /**
     * The form's Memory (JSON) field: blank keeps the stored setting, content
     * must be a JSON object the memory capability can read.
     */
    private Optional<Map<String, Object>> memory(FormBody body) {
        String json = body.str("memoryConfig");
        if (json == null || json.isBlank()) return Optional.empty();
        Map<String, Object> memory;
        try {
            memory = mapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid memory config: " + e.getMessage(), e);
        }
        MemoryPlugin.check(memory);
        return Optional.of(memory);
    }

    /** The tool the form describes, under {@code toolId} — a fresh id for a new tool. */
    AgentTool tool(AgentToolId toolId, FormBody body) {
        // Name: prefer explicit custom name, fall back to selected builtin name.
        String builtin = body.str("builtinName");
        String name = body.str("name");
        if (name == null || name.isBlank()) name = builtin;
        if (name == null || name.equals("custom")) name = "";
        Map<String, Object> overrides;
        try {
            // Accept both the new "overrides" field and the legacy "toolConfig" field
            // so a partially-deployed admin UI doesn't lose data on save.
            String json = body.str("overrides");
            if (json == null || json.isBlank()) json = body.str("toolConfig");
            overrides = (json != null && !json.isBlank())
                    ? mapper.readValue(json, new TypeReference<>() {})
                    : Map.of();
        } catch (Exception e) {
            overrides = Map.of();
        }
        // Renamed builtin: the custom name is what the LLM sees; the alias
        // override keeps resolution pointing at the selected registry tool.
        if (builtin != null && !builtin.isBlank() && !builtin.equals("custom")
                && !builtin.equals(name) && !overrides.containsKey("tool")) {
            overrides = new java.util.LinkedHashMap<>(overrides);
            overrides.put("tool", builtin);
        }
        boolean deferred = Boolean.TRUE.equals(body.bool("deferred"));
        boolean needsApproval = Boolean.TRUE.equals(body.bool("needsApproval"));
        // Blank/invalid → null → no per-tool cap (the runtime safety cap still applies).
        Integer maxResultChars = null;
        String maxChars = body.str("maxResultChars");
        if (maxChars != null && !maxChars.isBlank()) {
            try {
                int parsed = Integer.parseInt(maxChars.trim());
                if (parsed > 0) maxResultChars = parsed;
            } catch (NumberFormatException ignored) {
            }
        }
        // A tool an agent names is one it may use: there is no switching one off, only removing it.
        return new AgentTool(toolId, name, body.str("description"), overrides, true, deferred,
                needsApproval, maxResultChars);
    }
}
