package ai.mindconnect.filestore;

import java.time.Instant;

/**
 * Metadata of one stored file, addressed by its generated id — the mindconnect
 * equivalent of an OpenAI Files-API file object. Content is read through the
 * {@link FileStore}, never by path: chats and vector stores reference the id,
 * and only the backend knows where the bytes live (disk, object storage, db).
 */
public record StoredFile(
        FileId id,
        String name,
        String contentType,
        long size,
        Instant createdAt
) {

    public StoredFile {
        if (id == null) {
            throw new IllegalArgumentException("A stored file needs an id");
        }
    }
}
