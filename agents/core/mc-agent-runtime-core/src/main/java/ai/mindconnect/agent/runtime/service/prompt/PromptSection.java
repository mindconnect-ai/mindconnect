package ai.mindconnect.agent.runtime.service.prompt;

import ai.mindconnect.agent.runtime.domain.AgentDefinition;
import ai.mindconnect.agent.runtime.domain.AgentSession;

/**
 * A section a feature adds to every system prompt, rendered fresh each
 * round — where a {@code PromptContextProvider} offers a variable the
 * agent's template may use, a section is there whether the template asks
 * for it or not. Contributed with {@code ctx.contribute(PromptSection.class, …)};
 * {@link SystemPromptRenderer} puts the sections after the user's and the
 * project's instructions, in contribution order.
 */
@FunctionalInterface
public interface PromptSection {

    /**
     * The section, starting with its own blank lines and heading
     * ({@code "\n\n## …"}), or an empty string when there is nothing to add
     * for this agent and session.
     */
    String render(AgentDefinition def, AgentSession session);
}
