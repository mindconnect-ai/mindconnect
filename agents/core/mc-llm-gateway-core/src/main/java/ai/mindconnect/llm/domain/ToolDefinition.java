package ai.mindconnect.llm.domain;

import java.util.Map;

public record ToolDefinition(
        String name,
        String description,
        Map<String, Object> parametersSchema
) {
    public static ToolDefinition of(String name, String description, Map<String, Object> parametersSchema) {
        return new ToolDefinition(name, description, parametersSchema);
    }
}
