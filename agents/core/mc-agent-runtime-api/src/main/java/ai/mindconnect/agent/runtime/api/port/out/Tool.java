package ai.mindconnect.agent.runtime.api.port.out;

import ai.mindconnect.agent.runtime.api.domain.Session;
import ai.mindconnect.agent.runtime.api.domain.ToolCall;
import ai.mindconnect.agent.runtime.api.domain.ToolDefinition;
import ai.mindconnect.agent.runtime.api.domain.ToolOutput;

/** A tool: what the model is told about it, and what running it does. */
public interface Tool {

    ToolDefinition definition();

    /**
     * Runs the call to its end. An error the model should read is an output,
     * not an exception.
     *
     * @param session the session the call belongs to — where a tool learns
     *                for whom and where it works
     */
    ToolOutput execute(ToolCall call, Session session);
}
