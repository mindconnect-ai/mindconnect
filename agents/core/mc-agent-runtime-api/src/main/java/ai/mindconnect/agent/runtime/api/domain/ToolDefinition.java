package ai.mindconnect.agent.runtime.api.domain;

import java.util.Map;

/**
 * A tool as the model sees it.
 *
 * @param parametersSchema the arguments as a JSON schema
 */
public record ToolDefinition(String name, String description, Map<String, Object> parametersSchema) { }
