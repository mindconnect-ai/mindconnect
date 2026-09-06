package ai.mindconnect.agent.port.out;

import java.util.Optional;

/**
 * Reads the bytes behind a media content part — the file a
 * {@link ai.mindconnect.message.domain.ContentPart.Media} references by id.
 * The conversation record carries the reference; the message mapper turns it
 * into the base64 the gateway sends, and this is where it gets the bytes.
 *
 * <p>An out-port because the runtime core does not know where files live.
 * The host adapts its file store; a host without one uses {@link #none()},
 * and every media part renders as its placeholder.
 */
public interface PartContentReader {

    /** The file's bytes and media type, or empty when the id is unknown or the file is gone. */
    Optional<Content> read(String fileId);

    /** What a read hands back. {@code mediaType} may be null when the store did not record one. */
    record Content(byte[] bytes, String mediaType) {}

    /** A reader that finds nothing — for hosts without a file store. */
    static PartContentReader none() {
        return fileId -> Optional.empty();
    }
}
