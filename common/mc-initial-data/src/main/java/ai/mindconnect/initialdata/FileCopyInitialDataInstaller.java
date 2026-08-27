package ai.mindconnect.initialdata;

import org.springframework.core.io.Resource;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Default {@link InitialDataInstaller}: copies each bundled resource into a
 * target directory verbatim, keeping its file name. An entry "exists" when a
 * file of that name is already in the directory, so user files are never
 * overwritten.
 *
 * <p>Suitable for file-backed stores — point it at the same directory the store
 * reads from.
 */
public class FileCopyInitialDataInstaller implements InitialDataInstaller {

    private final Path targetDir;

    public FileCopyInitialDataInstaller(Path targetDir) {
        this.targetDir = targetDir;
        try {
            Files.createDirectories(targetDir);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot create initial-data target dir: " + targetDir, e);
        }
    }

    @Override
    public boolean exists(String id) {
        // Any file starting with "<id>." (whatever the extension) counts.
        try {
            return Files.list(targetDir)
                    .anyMatch(p -> {
                        String name = p.getFileName().toString();
                        return name.equals(id) || name.startsWith(id + ".");
                    });
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot list " + targetDir, e);
        }
    }

    @Override
    public void install(String id, Resource resource) throws IOException {
        Path dest = targetDir.resolve(resource.getFilename());
        try (InputStream in = resource.getInputStream()) {
            Files.copy(in, dest);
        }
    }
}
