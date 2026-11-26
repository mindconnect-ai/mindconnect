package ai.mindconnect.agent.runtime.api.port.out;

import java.util.Optional;

import ai.mindconnect.agent.loop.domain.ContentPart.MediaSource;

/**
 * Where the content of a stored file comes from. A message keeps a file as
 * a reference, so a conversation stays small; whoever sends the message to
 * a model asks here for what the reference stands for. A host adapts its
 * file store; without one, {@link #none()} finds nothing and a model is
 * told about the file instead of being shown it.
 */
public interface MediaReader {

    /** The file's content with its media type; empty for a file that is unknown here or gone. */
    Optional<MediaSource.Inline> read(MediaSource.FileId file);

    static MediaReader none() {
        return file -> Optional.empty();
    }
}
