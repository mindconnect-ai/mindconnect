package ai.mindconnect.filestore.filesystem;

import ai.mindconnect.agent.Namespace;
import ai.mindconnect.filestore.FileStore;
import ai.mindconnect.filestore.FileStoreBackend;

import java.nio.file.Path;
import java.util.Map;

/**
 * The built-in backend. Config: {@code baseDir} — the data directory (default
 * {@code data}); {@code namespace} — required. The files live under
 * {@code <baseDir>/<namespace>/files}.
 */
public final class FilesystemFileStoreBackend implements FileStoreBackend {

    @Override
    public String type() {
        return "filesystem";
    }

    @Override
    public FileStore open(Map<String, String> config) {
        String baseDir = config == null ? null : config.get("baseDir");
        String namespace = config == null ? null : config.get("namespace");
        if (namespace == null || namespace.isBlank()) {
            throw new IllegalArgumentException("filesystem file-store backend requires the 'namespace' config key");
        }
        return new FilesystemFileStore(Path.of(baseDir == null || baseDir.isBlank() ? "data" : baseDir), new Namespace(namespace));
    }
}
