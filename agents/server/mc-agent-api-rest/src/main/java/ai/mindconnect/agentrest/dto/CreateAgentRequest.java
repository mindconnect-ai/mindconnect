package ai.mindconnect.agentrest.dto;

public record CreateAgentRequest(
        String namespace,
        String name,
        String description,
        String systemPrompt,
        String welcomeMessage,
        String llmConfigName
) {}
