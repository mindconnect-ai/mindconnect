package ai.mindconnect.agent.adapter.filestore;

import ai.mindconnect.agent.port.out.PartContentReader;
import ai.mindconnect.filestore.FileStore;
import ai.mindconnect.filestore.StoredFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import java.util.Optional;

/**
 * {@link PartContentReader} over the host's {@link FileStore}: a media part's
 * file id is a file-store id, its bytes are the stored content, its media
 * type what the upload recorded. A missing or unreadable file reads as
 * empty — the mapper renders a placeholder, the turn goes on.
 */
public final class FileStorePartContentReader implements PartContentReader {

    private static final Logger log = LoggerFactory.getLogger(FileStorePartContentReader.class);

    private final FileStore fileStore;

    public FileStorePartContentReader(FileStore fileStore) {
        this.fileStore = Objects.requireNonNull(fileStore, "fileStore");
    }

    @Override
    public Optional<Content> read(String fileId) {
        if (fileId == null) return Optional.empty();
        StoredFile file = fileStore.find(fileId).orElse(null);
        if (file == null) return Optional.empty();
        try (InputStream in = fileStore.content(fileId)) {
            return Optional.of(new Content(in.readAllBytes(), file.contentType()));
        } catch (IOException e) {
            log.warn("Could not read file {} ({}): {}", fileId, file.name(), e.getMessage());
            return Optional.empty();
        }
    }
}
