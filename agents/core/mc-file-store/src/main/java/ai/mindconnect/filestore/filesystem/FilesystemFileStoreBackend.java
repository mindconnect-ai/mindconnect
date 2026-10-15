package ai.mindconnect.filestore.filesystem;

import ai.mindconnect.agent.ScopeSupplier;
import ai.mindconnect.filestore.FileStore;
import ai.mindconnect.filestore.FileStoreBackend;

import java.nio.file.Path;
import java.util.Map;

/**
 * The built-in backend. Config: {@code baseDir} — the data directory (default
 * {@code data}). The files live under {@code <baseDir>/<namespace>/files}, the
 * namespace the scope names per call.
 */
public final class FilesystemFileStoreBackend implements FileStoreBackend {

    @Override
    public String type() {
        return "filesystem";
    }

    @Override
    public FileStore open(Map<String, String> config, ScopeSupplier scope) {
        if (scope == null) {
            throw new IllegalArgumentException("filesystem file-store backend requires a scope");
        }
        String baseDir = config == null ? null : config.get("baseDir");
        return new FilesystemFileStore(Path.of(baseDir == null || baseDir.isBlank() ? "data" : baseDir), scope);
    }
}
