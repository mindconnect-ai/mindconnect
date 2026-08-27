package ai.mindconnect.filestore.filesystem;

import ai.mindconnect.filestore.FileStore;
import ai.mindconnect.filestore.FileStoreBackend;

import java.nio.file.Path;
import java.util.Map;

/** The built-in backend. Config: {@code dir} — store root (default {@code data/files}). */
public final class FilesystemFileStoreBackend implements FileStoreBackend {

    @Override
    public String type() {
        return "filesystem";
    }

    @Override
    public FileStore open(Map<String, String> config) {
        String dir = config == null ? null : config.get("dir");
        return new FilesystemFileStore(Path.of(dir == null || dir.isBlank() ? "data/files" : dir));
    }
}
