package ai.mindconnect.agent.runtime.service.prompt;

import ai.mindconnect.agent.runtime.domain.AgentDefinition;
import ai.mindconnect.agent.runtime.domain.AgentSession;
import ai.mindconnect.agent.runtime.domain.AttachedFile;
import ai.mindconnect.agent.runtime.memory.port.in.MemoryStrategy;
import ai.mindconnect.agent.runtime.port.out.PromptRenderer;
import ai.mindconnect.agent.AuthenticationInfo;

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
        return prompt + workingDirSection(session) + attachedFilesSection(session);
    }

    /**
     * Names the session's working directory, so the model knows where it is
     * — what {@code .} means, where relative paths land, where {@code bash}
     * runs — without probing for it. Rendered fresh every round: a
     * {@code /cd} shows up on the next turn.
     */
    static String workingDirSection(AgentSession session) {
        if (session == null || (!session.hasWorkingDir() && session.additionalDirs().isEmpty())) return "";
        StringBuilder out = new StringBuilder("\n\n## Working directory\n");
        if (session.hasWorkingDir()) {
            out.append("You are working in `").append(session.workingDir()).append("`. Relative paths in the "
                    + "file tools resolve against it, `bash` runs in it, and `.` means this directory.");
        }
        if (!session.additionalDirs().isEmpty()) {
            out.append(session.hasWorkingDir() ? " You may also use these directories, by absolute path:"
                    : "You may use these directories, by absolute path:");
            for (String dir : session.additionalDirs()) {
                out.append("\n- `").append(dir).append('`');
            }
        }
        return out.toString();
    }

    /**
     * Announces chat-attached files so the model actually reaches for
     * vector_search instead of claiming it cannot see the file. Rendered
     * fresh every round from the session, so the list is always current —
     * a removed file disappears from the prompt with it.
     */
    static String attachedFilesSection(AgentSession session) {
        if (session == null) return "";
        // Images are not indexed — they travel with the message as image
        // parts (or their placeholders) and have no business in this list.
        java.util.List<ai.mindconnect.agent.runtime.domain.AttachedFile> searchable = session.attachedFiles().stream()
                .filter(f -> !f.isImage())
                .toList();
        if (searchable.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder("\n\n## Attached files\n"
                + "The user attached these files to this conversation:\n");
        boolean anyOnDisk = false;
        for (var file : searchable) {
            out.append("- ").append(file.name()).append(" (").append(AttachmentNotice.kind(file.name())).append(")");
            if (file.hasPath()) {
                out.append(" — on disk at `").append(file.path()).append('`');
                anyOnDisk = true;
            }
            out.append('\n');
        }
        out.append("Their content is indexed for semantic search. To answer anything about them, "
                + "call `vector_search` with your question (no `store` argument needed) and read the "
                + "returned chunks.");
        if (anyOnDisk) {
            out.append(" A file with a path is also a file on disk — `file_read`, `document_outline`, "
                    + "`read_document`, `grep_document` and `bash` open it by that path, for the parts "
                    + "a search does not surface.");
        }
        if (searchable.stream().anyMatch(f -> !f.hasPath())) {
            out.append(" A file without a path is NOT on the filesystem: `file_read`, `document_outline`, "
                    + "`read_document`, `grep_document` and `bash` cannot open it, and its name is not a path.");
        }
        return out.toString();
    }
}
