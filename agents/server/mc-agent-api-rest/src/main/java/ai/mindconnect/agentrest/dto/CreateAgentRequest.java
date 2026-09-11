package ai.mindconnect.agentrest.dto;

public record CreateAgentRequest(
        String name,
        String description,
        String systemPrompt,
        String welcomeMessage,
        String llmConfigName
) {}
