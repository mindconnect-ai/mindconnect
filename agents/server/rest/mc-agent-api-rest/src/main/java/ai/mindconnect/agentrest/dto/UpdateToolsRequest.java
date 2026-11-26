package ai.mindconnect.agentrest.dto;

import ai.mindconnect.agent.management.format.AgentToolJson;

import java.util.List;

public record UpdateToolsRequest(List<AgentToolJson> tools) {}
