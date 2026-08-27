package ai.mindconnect.agent.service.prompt;

import ai.mindconnect.agent.domain.AgentDefinition;
import ai.mindconnect.agent.domain.AgentSession;
import ai.mindconnect.agent.memory.port.in.MemoryStrategy;
import ai.mindconnect.agent.port.out.PromptRenderer;
import ai.mindconnect.common.AuthenticationInfo;

/**
 * Builds the full system prompt the LLM sees: the agent's rendered template
 * followed by the memory strategy's optional addendum (e.g. compressed
 * conversation summaries).
 */
public final class SystemPromptRenderer {

    private SystemPromptRenderer() {}

    public static String render(PromptRenderer renderer,
                                MemoryStrategy strategy,
                                AgentDefinition def,
                                AgentSession session,
                                AuthenticationInfo auth) {
        String rendered = renderer.render(def.systemPrompt(), def, session, auth);
        String addendum = strategy.systemPromptAddendum(def, session);
        String prompt = (addendum == null || addendum.isEmpty()) ? rendered : rendered + addendum;
        return prompt + attachedFilesSection(session);
    }

    /**
     * Announces chat-attached files so the model actually reaches for
     * vector_search instead of claiming it cannot see the file. Rendered
     * fresh every round from the session, so the list is always current —
     * a removed file disappears from the prompt with it.
     */
    private static String attachedFilesSection(AgentSession session) {
        if (session == null || session.attachedFiles().isEmpty()) {
            return "";
        }
        return "\n\n## Attached files\n"
                + "The user attached these files to this conversation:\n"
                + session.attachedFiles().stream().map(f -> "- " + f)
                        .reduce((a, b) -> a + "\n" + b).orElse("")
                + "\nTheir content is indexed for semantic search. Use the vector_search tool "
                + "(no 'store' argument needed) to look inside them before answering questions "
                + "about them.";
    }
}
