package ai.mindconnect.agent.port.out;

import ai.mindconnect.agent.domain.AgentDefinition;
import ai.mindconnect.agent.domain.AgentSession;
import ai.mindconnect.common.AuthenticationInfo;

import java.util.Map;

/**
 * Renders a system-prompt template against a context populated by all registered
 * {@link PromptContextProvider}s.
 * <p>
 * Templates without templating syntax pass through unchanged — backward-compatible
 * with plain-text prompts. The default implementation uses Pebble (Jinja2-style)
 * for {@code {{ var }}}, {@code {% if %}} and {@code {% for %}} constructs.
 */
public interface PromptRenderer {

    /**
     * Renders {@code template} for the given agent invocation context.
     * Returns the unchanged template string if rendering fails — callers should
     * never crash because of a bad template.
     */
    default String render(String template, AgentDefinition def, AgentSession session, AuthenticationInfo auth) {
        return render(template, def, session, auth, Map.of());
    }

    /**
     * Same as {@link #render(String, AgentDefinition, AgentSession, AuthenticationInfo)},
     * but with an extra map of variables that override values produced by registered
     * {@link PromptContextProvider}s. Used by call sites that need to inject ad-hoc
     * data into a sub-agent's prompt (e.g. response reviewers receiving the user
     * message and the agent's draft answer).
     */
    String render(String template, AgentDefinition def, AgentSession session, AuthenticationInfo auth,
                  Map<String, Object> extraVars);
}
