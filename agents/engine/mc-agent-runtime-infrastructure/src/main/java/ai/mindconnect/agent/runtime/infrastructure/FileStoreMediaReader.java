package ai.mindconnect.agent.runtime.infrastructure;

import java.io.IOException;
import java.io.InputStream;
import java.util.Base64;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import ai.mindconnect.agent.loop.domain.ContentPart.MediaSource;
import ai.mindconnect.agent.runtime.api.port.out.MediaReader;
import ai.mindconnect.filestore.FileId;
import ai.mindconnect.filestore.FileStore;

/** The content of a file a message refers to, from the application's file store. */
final class FileStoreMediaReader implements MediaReader {

    private static final Logger log = LoggerFactory.getLogger(FileStoreMediaReader.class);

    private final FileStore files;

    FileStoreMediaReader(FileStore files) {
        this.files = files;
    }

    @Override
    public Optional<MediaSource.Inline> read(MediaSource.FileId file) {
        FileId id = FileId.of(file.fileId());
        return files.find(id).flatMap(stored -> {
            try (InputStream content = files.content(id)) {
                return Optional.of(new MediaSource.Inline(
                        Base64.getEncoder().encodeToString(content.readAllBytes()), stored.contentType()));
            } catch (IOException e) {
                log.warn("Could not read file {} ({}): {}", id, stored.name(), e.getMessage());
                return Optional.empty();
            }
        });
    }
}
