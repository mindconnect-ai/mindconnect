package ai.mindconnect.filestore;

import ai.mindconnect.agent.UserId;

import java.time.Instant;

/**
 * Metadata of one stored file, addressed by its generated id — the mindconnect
 * equivalent of an OpenAI Files-API file object. Content is read through the
 * {@link FileStore}, never by path: chats and vector stores reference the id,
 * and only the backend knows where the bytes live (disk, object storage, db).
 *
 * @param creator the user the file was stored for; {@code null} for a file
 *                stored on nobody's behalf — by an embedding library, or
 *                before creators were recorded
 */
public record StoredFile(
        FileId id,
        String name,
        String contentType,
        long size,
        Instant createdAt,
        UserId creator
) {

    public StoredFile {
        if (id == null) {
            throw new IllegalArgumentException("A stored file needs an id");
        }
    }

    /** A file without a creator. */
    public StoredFile(FileId id, String name, String contentType, long size, Instant createdAt) {
        this(id, name, contentType, size, createdAt, null);
    }

    /** Whether {@code user} is the recorded creator. A file without one belongs to nobody. */
    public boolean createdBy(UserId user) {
        return creator != null && creator.equals(user);
    }

    /**
     * Whether {@code user} may read the file by its id: its creator may, and
     * so may anyone for a file without a creator. Those were stored before
     * creators were recorded and are still referenced by id from chats and
     * transcripts; their random ids are what kept them private so far, and
     * locking them away would break those references for their owners.
     */
    public boolean readableBy(UserId user) {
        return creator == null || creator.equals(user);
    }
}
