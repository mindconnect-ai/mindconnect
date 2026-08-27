package ai.mindconnect.agent.tools.builtin;

import ai.mindconnect.agent.tool.AgentTool;
import ai.mindconnect.agent.tool.Tool;
import ai.mindconnect.agent.tool.ToolCallScope;
import ai.mindconnect.agent.tool.ToolEnvironment;
import ai.mindconnect.agent.tool.ToolFactory;
import ai.mindconnect.agent.port.out.AgentSessionRepository;
import ai.mindconnect.message.port.out.MessageRepository;

public final class FetchToolResultToolFactory implements ToolFactory {
    private MessageRepository messageRepository;
    private AgentSessionRepository sessionRepository;

    @Override public String name() { return "fetch_tool_result"; }

    @Override public String group() { return "agents"; }

    @Override public void bind(ToolEnvironment env) {
        this.messageRepository = env.require(MessageRepository.class);
        this.sessionRepository = env.require(AgentSessionRepository.class);
    }

    @Override public Tool create(AgentTool agentTool, ToolCallScope scope) {
        return new FetchToolResultTool(messageRepository, sessionRepository, scope.sessionId());
    }
}
