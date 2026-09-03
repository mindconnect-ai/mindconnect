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
    static String attachedFilesSection(AgentSession session) {
        if (session == null || session.attachedFiles().isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder("\n\n## Attached files\n"
                + "The user attached these files to this conversation:\n");
        for (String file : session.attachedFiles()) {
            out.append("- ").append(file).append(" (").append(AttachmentNotice.kind(file)).append(")\n");
        }
        return out.append("Their content is indexed for semantic search. To answer anything about them, "
                + "call `vector_search` with your question (no `store` argument needed) and read the "
                + "returned chunks. They are NOT files on the filesystem: `file_read`, `document_outline`, "
                + "`read_document`, `grep_document` and `bash` cannot open them, and a file name is not a path.")
                .toString();
    }
}
