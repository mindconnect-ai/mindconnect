package ai.mindconnect.agent.runtime.service.prompt;

import ai.mindconnect.agent.runtime.domain.AgentDefinition;
import ai.mindconnect.agent.runtime.domain.AgentSession;
import ai.mindconnect.agent.runtime.domain.AttachedFile;
import ai.mindconnect.agent.runtime.memory.port.in.MemoryStrategy;
import ai.mindconnect.agent.runtime.port.out.PromptRenderer;
import ai.mindconnect.agent.runtime.skill.SkillCatalog;
import ai.mindconnect.agent.AuthenticationInfo;

/**
 * Builds the full system prompt the LLM sees: the agent's rendered template,
 * the memory strategy's optional addendum (e.g. compressed conversation
 * summaries), then the sections the runtime adds itself — where the session
 * works, the user's standing instructions, what the project asks of an agent
 * working there, which skills it can load, and which files are attached to
 * the chat.
 */
public final class SystemPromptRenderer {

    private SystemPromptRenderer() {}

    /** Without a skill catalog: an assembly that has no skills. */
    public static String render(PromptRenderer renderer,
                                MemoryStrategy strategy,
                                AgentDefinition def,
                                AgentSession session,
                                AuthenticationInfo auth,
                                InstructionFiles instructions) {
        return render(renderer, strategy, def, session, auth, instructions, SkillCatalog.none());
    }

    public static String render(PromptRenderer renderer,
                                MemoryStrategy strategy,
                                AgentDefinition def,
                                AgentSession session,
                                AuthenticationInfo auth,
                                InstructionFiles instructions,
                                SkillCatalog skills) {
        String rendered = renderer.render(def.systemPrompt(), def, session, auth);
        String addendum = strategy.systemPromptAddendum(def, session);
        String prompt = (addendum == null || addendum.isEmpty()) ? rendered : rendered + addendum;
        return prompt + workingDirSection(session)
                + instructions.userSection(session) + instructions.projectSection(session)
                + (skills == null ? "" : skills.promptSection(def, session))
                + attachedFilesSection(session);
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
        // parts (or their placeholders), so they stay out of the list that
        // talks about searching and get their own line below.
        java.util.List<ai.mindconnect.agent.runtime.domain.AttachedFile> searchable = session.attachedFiles().stream()
                .filter(f -> !f.isImage())
                .toList();
        java.util.List<ai.mindconnect.agent.runtime.domain.AttachedFile> images = session.attachedFiles().stream()
                .filter(f -> f.isImage() && f.hasPath())
                .toList();
        if (searchable.isEmpty()) {
            return imagesSection(session, images);
        }
        StringBuilder out = new StringBuilder("\n\n## Attached files\n"
                + "The user attached these files to this conversation:\n");
        boolean anyOnDisk = false;
        for (var file : searchable) {
            out.append("- ").append(file.name()).append(" (").append(AttachmentNotice.kind(file.name())).append(")");
            if (file.hasPath()) {
                out.append(" — on disk at `").append(file.path()).append('`');
                String inWorkingDir = relativeToWorkingDir(session, file.path());
                if (inWorkingDir != null) {
                    out.append(", i.e. `").append(inWorkingDir).append("` from the working directory");
                }
                anyOnDisk = true;
            }
            out.append('\n');
        }
        if (anyOnDisk) {
            out.append("A file with a path is a file on disk: open it by that path — `file_read` for "
                    + "text and code, `document_outline`, `document_sections`, `read_document` and "
                    + "`grep_document` for PDF and Word — whenever the exact content, the structure or "
                    + "a passage in context is what the question needs. What the file says as a whole, "
                    + "which sections it has and in which order: open it, never search, because search "
                    + "ranks fragments by similarity and drops the rest. ");
        }
        out.append("Their content is also indexed for semantic search: `vector_search` with your "
                + "question (no `store` argument needed) finds passages across all of them, which is "
                + "the way into a long document and the only way to a file without a path.");
        if (searchable.stream().anyMatch(f -> !f.hasPath())) {
            out.append(" A file without a path is NOT on the filesystem: `file_read`, `document_outline`, "
                    + "`read_document`, `grep_document` and `bash` cannot open it, and its name is not a path.");
        }
        out.append(imagesLines(session, images));
        return out.toString();
    }

    /** The images' own section, for a chat that attached nothing else. */
    private static String imagesSection(AgentSession session,
                                        java.util.List<ai.mindconnect.agent.runtime.domain.AttachedFile> images) {
        if (images.isEmpty()) return "";
        return "\n\n## Attached files" + imagesLines(session, images);
    }

    /**
     * Where the attached images are. You see an image in the message itself —
     * this says where its file is, for the times the question is about the
     * file rather than the picture: convert it, move it, pass it to a script.
     */
    private static String imagesLines(AgentSession session,
                                      java.util.List<ai.mindconnect.agent.runtime.domain.AttachedFile> images) {
        if (images.isEmpty()) return "";
        StringBuilder out = new StringBuilder("\nThe images the user attached are shown to you in the "
                + "conversation; each one is also a file on disk:\n");
        for (var image : images) {
            out.append("- ").append(image.name()).append(" — `").append(image.path()).append('`');
            String inWorkingDir = relativeToWorkingDir(session, image.path());
            if (inWorkingDir != null) {
                out.append(", i.e. `").append(inWorkingDir).append("` from the working directory");
            }
            out.append('\n');
        }
        return out.append("Their content is not indexed — `vector_search` does not find them.").toString();
    }

    /**
     * The file's path as the tools take it here: relative to the session's
     * working directory, which is what a model types after reading this
     * section. Null when the session works elsewhere — then the absolute
     * path is the only one that works.
     */
    private static String relativeToWorkingDir(AgentSession session, String path) {
        if (path == null || !session.hasWorkingDir()) return null;
        try {
            java.nio.file.Path base = java.nio.file.Path.of(session.workingDir()).toAbsolutePath().normalize();
            java.nio.file.Path file = java.nio.file.Path.of(path).toAbsolutePath().normalize();
            return file.startsWith(base) && !file.equals(base) ? base.relativize(file).toString() : null;
        } catch (RuntimeException e) {
            return null;
        }
    }
}
