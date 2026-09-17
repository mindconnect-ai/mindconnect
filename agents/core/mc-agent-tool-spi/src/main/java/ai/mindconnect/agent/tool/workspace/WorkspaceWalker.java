package ai.mindconnect.agent.tool.workspace;

/**
 * Receives a depth-first walk over a workspace tree, directories before their
 * contents. Symbolic links are reported, never followed.
 */
public interface WorkspaceWalker {

    enum Step { CONTINUE, SKIP_SUBTREE, TERMINATE }

    /** A directory, before its contents; the start directory included. */
    Step directory(WorkspaceEntry directory);

    /** Anything that is not a directory: files, links, special files. */
    Step file(WorkspaceEntry file);
}
