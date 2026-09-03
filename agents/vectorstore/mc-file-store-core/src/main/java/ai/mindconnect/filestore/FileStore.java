package ai.mindconnect.filestore;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Optional;

/**
 * Id-addressed file storage. Deliberately small: save bytes once, read
 * metadata or content by id, delete by id. Which chat or vector store uses a
 * file is somebody else's association. Content access is stream-based so
 * consumers (e.g. ingestion) stay backend-agnostic — a filesystem backend
 * streams from disk, an s3 backend from the bucket.
 */
public interface FileStore {

    /** Stores the content and returns the new file's metadata (with generated id). */
    StoredFile save(String name, String contentType, InputStream content) throws IOException;

    Optional<StoredFile> find(String id);

    /** The stored content; the caller closes the stream. */
    InputStream content(String id) throws IOException;

    List<StoredFile> list();

    void delete(String id) throws IOException;
}
