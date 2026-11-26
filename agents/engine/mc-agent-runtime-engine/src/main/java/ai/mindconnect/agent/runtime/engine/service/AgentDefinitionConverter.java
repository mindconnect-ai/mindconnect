package ai.mindconnect.agent.runtime.engine.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import ai.mindconnect.agent.runtime.api.domain.AgentDefinition;
import ai.mindconnect.agent.runtime.api.domain.ModelSettings;
import ai.mindconnect.agent.runtime.api.domain.ToolBinding;

/**
 * An {@link AgentDefinition} as a JSON object and back: how a turn's task
 * and a stored session keep the agent they were given. Plain maps and
 * lists, like {@link ai.mindconnect.agent.loop.domain.TurnMessageConverter}.
 */
public final class AgentDefinitionConverter {

    private static final String MODEL_SETTINGS = "modelSettings";

    public Map<String, Object> toJson(AgentDefinition agent) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("name", agent.name());
        json.put("description", agent.description());
        json.put("instructions", agent.instructions());
        json.put(MODEL_SETTINGS, settingsToJson(agent.modelSettings()));
        json.put("tools", agent.tools().stream()
                .map(tool -> Map.<String, Object>of("name", tool.name(), "deferred", tool.deferred(),
                        "metadata", tool.metadata())).toList());
        json.put("maxRounds", agent.maxRounds());
        json.put("metadata", agent.metadata());
        return json;
    }

    @SuppressWarnings("unchecked")
    public AgentDefinition fromJson(Map<String, Object> json) {
        return new AgentDefinition((String) json.get("name"), (String) json.get("description"),
                (String) json.get("instructions"),
                settingsFromJson((Map<String, Object>) json.get(MODEL_SETTINGS)),
                ((List<Map<String, Object>>) json.get("tools")).stream()
                        .map(tool -> new ToolBinding((String) tool.get("name"), Boolean.TRUE.equals(tool.get("deferred")),
                                tool.get("metadata") instanceof Map<?, ?> metadata
                                        ? (Map<String, Object>) metadata : Map.of()))
                        .toList(),
                ((Number) json.get("maxRounds")).intValue(),
                json.get("metadata") instanceof Map<?, ?> metadata ? (Map<String, Object>) metadata : Map.of());
    }

    private static Map<String, Object> settingsToJson(ModelSettings settings) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("model", settings.model());
        json.put("temperature", settings.temperature());
        json.put("maxOutputTokens", settings.maxOutputTokens());
        json.put("reasoningEffort", settings.reasoningEffort());
        json.put("extra", settings.extra());
        return json;
    }

    @SuppressWarnings("unchecked")
    private static ModelSettings settingsFromJson(Map<String, Object> json) {
        return new ModelSettings((String) json.get("model"),
                json.get("temperature") instanceof Number temperature ? temperature.doubleValue() : null,
                json.get("maxOutputTokens") instanceof Number limit ? limit.intValue() : null,
                (String) json.get("reasoningEffort"),
                (Map<String, Object>) json.get("extra"));
    }
}
