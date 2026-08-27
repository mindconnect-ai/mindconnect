package ai.mindconnect.agentrest.dto;

import ai.mindconnect.agent.tool.AgentTool;

import java.util.List;

public record UpdateToolsRequest(List<AgentTool> tools) {}
