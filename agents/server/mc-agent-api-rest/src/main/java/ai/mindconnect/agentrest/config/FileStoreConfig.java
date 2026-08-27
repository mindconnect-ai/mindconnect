package ai.mindconnect.agentrest.config;

import ai.mindconnect.filestore.FileStore;
import ai.mindconnect.filestore.FileStoreBackend;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

/**
 * The application-wide {@link FileStore}: backend selected by
 * {@code mindconnect.file-store.backend} (SPI, {@code filesystem} built-in;
 * s3/db backends register the same way from their own modules). One bean so
 * the Files API and the chat-session attach endpoints share the same store.
 */
@Configuration
public class FileStoreConfig {

    @Bean
    @ConditionalOnMissingBean(FileStore.class)
    FileStore fileStore(@Value("${mindconnect.file-store.backend:filesystem}") String backend,
                        @Value("${mindconnect.file-store.dir:data/files}") String dir) {
        return FileStoreBackend.byType(backend)
                .orElseThrow(() -> new IllegalStateException("No file-store backend '" + backend
                        + "' on the classpath (available: "
                        + FileStoreBackend.discover().stream().map(FileStoreBackend::type).toList() + ")"))
                .open(Map.of("dir", dir));
    }
}
