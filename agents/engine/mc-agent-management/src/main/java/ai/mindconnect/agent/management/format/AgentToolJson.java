package ai.mindconnect.agent.management.format;

import java.util.List;
import java.util.Map;

import ai.mindconnect.agent.EntityId;
import ai.mindconnect.agent.tool.AgentTool;
import ai.mindconnect.agent.tool.AgentToolId;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * The persisted shape of an agent's tool binding, lenient where a hand-written
 * file is: a missing id gets a fresh one, missing flags take their defaults.
 * Older files carry {@code toolType}, {@code toolConfig}, {@code inputSchema} or
 * an {@code agentDefinitionId}; they are ignored, so those files keep loading.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AgentToolJson(String id, String name, String description, Map<String, Object> overrides,
                            Boolean enabled, Boolean deferred, Boolean needsApproval, Integer maxResultChars) {

    public static AgentToolJson of(AgentTool tool) {
        return new AgentToolJson(tool.id().value(), tool.name(), tool.description(), tool.overrides(),
                tool.enabled(), tool.deferred(), tool.needsApproval(), tool.maxResultChars());
    }

    public static List<AgentTool> tools(List<AgentToolJson> tools) {
        return tools == null ? List.of() : tools.stream().map(AgentToolJson::toTool).toList();
    }

    public static List<AgentToolJson> of(List<AgentTool> tools) {
        return tools == null ? List.of() : tools.stream().map(AgentToolJson::of).toList();
    }

    public AgentTool toTool() {
        return new AgentTool(
                AgentToolId.of(id == null ? EntityId.randomValue() : id),
                name, description, overrides,
                enabled == null || enabled,
                deferred != null && deferred,
                needsApproval != null && needsApproval,
                maxResultChars);
    }
}
