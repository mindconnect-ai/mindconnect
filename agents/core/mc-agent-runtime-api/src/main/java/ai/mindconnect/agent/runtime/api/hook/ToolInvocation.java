package ai.mindconnect.agent.runtime.api.hook;

import java.util.Optional;

import ai.mindconnect.agent.runtime.api.domain.InterruptionAnswer;
import ai.mindconnect.agent.runtime.api.domain.ToolCall;

/**
 * One run of a tool.
 *
 * @param answer what the user said to this call's tool approval, when the
 *               call was interrupted for one and is now run again. The hook
 *               that asked reads its own answer here; the engine does not
 *               interpret it
 */
public record ToolInvocation(AgentContext context, ToolCall call, Optional<InterruptionAnswer> answer) { }
