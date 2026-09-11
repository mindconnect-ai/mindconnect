package ai.mindconnect.agent.runtime.service.prompt;

import ai.mindconnect.agent.runtime.domain.AgentDefinition;
import ai.mindconnect.agent.runtime.domain.AgentSession;
import ai.mindconnect.agent.runtime.port.out.PromptContextProvider;
import ai.mindconnect.agent.AuthenticationInfo;

import java.util.Map;

/**
 * Exposes basic identifiers and metadata about the current agent invocation.
 * <p>
 * Variables provided:
 * <ul>
 *   <li>{@code agent_name}      — display name of the agent</li>
 *   <li>{@code agent_id}        — UUID string of the agent definition</li>
 *   <li>{@code user_id}         — user identifier (may be null in stateless flows)</li>
 *   <li>{@code session_id}      — UUID string of the session (may be null)</li>
 * </ul>
 */
public class AgentMetadataProvider implements PromptContextProvider {

    @Override
    public void contribute(Map<String, Object> ctx,
                           AgentDefinition def,
                           AgentSession session,
                           AuthenticationInfo auth) {
        if (def != null) {
            ctx.put("agent_name", def.name());
            ctx.put("agent_id", def.id() != null ? def.id().value() : null);
        }
        if (session != null) {
            ctx.put("user_id", session.userId());
            ctx.put("session_id", session.id() != null ? session.id().value() : null);
        }
    }
}
