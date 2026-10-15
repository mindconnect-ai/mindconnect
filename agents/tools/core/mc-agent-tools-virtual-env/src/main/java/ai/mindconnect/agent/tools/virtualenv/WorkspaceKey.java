package ai.mindconnect.agent.tools.virtualenv;

import ai.mindconnect.agent.tool.ToolCallScope;

/**
 * Which workspace a tool call works in: the template and the session key the
 * server addresses it by, plus the call's scope for the token.
 *
 * @param sessionKey the root session, so a sub-agent works in its parent's files
 */
public record WorkspaceKey(String template, String sessionKey, ToolCallScope scope) {

    /** Equal for the same workspace, whatever the calling agent. */
    String id() {
        return template + "/" + sessionKey;
    }
}
