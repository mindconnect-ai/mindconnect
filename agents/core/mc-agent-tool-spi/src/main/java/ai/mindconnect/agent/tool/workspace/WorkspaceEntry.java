package ai.mindconnect.agent.tool.workspace;

import java.nio.file.Path;

/**
 * What a workspace knows about one path, without following a symbolic link.
 *
 * @param path              absolute and normalised, in the workspace's own path space
 * @param directory         a directory (not a link to one)
 * @param regularFile       a regular file (not a link to one)
 * @param size              bytes of a regular file, 0 otherwise
 * @param lastModifiedMillis modification time, 0 when unknown
 */
public record WorkspaceEntry(Path path, boolean directory, boolean regularFile, long size, long lastModifiedMillis) {

    /** The last path element, e.g. {@code deck.pptx}. */
    public String name() {
        return path.getFileName() == null ? "" : path.getFileName().toString();
    }
}
