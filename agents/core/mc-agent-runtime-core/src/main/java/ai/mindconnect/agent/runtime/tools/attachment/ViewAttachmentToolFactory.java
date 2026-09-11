package ai.mindconnect.agent.runtime.tools.attachment;

import ai.mindconnect.agent.runtime.port.out.AgentSessionRepository;
import ai.mindconnect.agent.tool.AgentTool;
import ai.mindconnect.agent.tool.Tool;
import ai.mindconnect.agent.tool.ToolCallScope;
import ai.mindconnect.agent.tool.ToolEnvironment;
import ai.mindconnect.agent.tool.ToolFactory;
import ai.mindconnect.message.port.in.ConversationManager;

/**
 * Built into the runtime: needs only the session store and the
 * conversation manager, which every host has. Activated for a session when
 * an image or PDF is attached, so the agent has it exactly when a
 * placeholder may name it; assignable to an agent like any other tool.
 */
public final class ViewAttachmentToolFactory implements ToolFactory {

    private AgentSessionRepository sessions;
    private ConversationManager conversations;

    @Override public String name() { return ViewAttachmentTool.NAME; }

    @Override public String group() { return "attachments"; }

    @Override
    public void bind(ToolEnvironment env) {
        this.sessions = env.get(AgentSessionRepository.class).orElse(null);
        this.conversations = env.get(ConversationManager.class).orElse(null);
    }

    @Override
    public boolean isAvailable() {
        return sessions != null && conversations != null;
    }

    @Override
    public Tool create(AgentTool agentTool, ToolCallScope scope) {
        return new ViewAttachmentTool(sessions, conversations, scope.sessionId());
    }
}
